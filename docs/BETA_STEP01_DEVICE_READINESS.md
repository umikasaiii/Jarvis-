# JARVIS Offline Beta — PASSAGGIO 1/8 — Build verificata, installazione sicura, test Android reali

**Stato: CI READY.** Codice e APK verificati in CI; **nessuna verifica su telefono eseguita** (non è DEVICE VERIFIED).
**Aggiornamento in-place dell'installazione principale: NON AUTORIZZATO finché non completi e confermi la lista GO/NO-GO del §4.**
Nessun test fisico è stato simulato o dato per riuscito. Data: 2026-11-08.

---

## 1. Stato iniziale verificato

| Riferimento | Atteso | Verificato |
|---|---|---|
| Branch di verifica | `claude/step02-db-hardening-validation` | ✔ (HEAD di partenza `929cd66` come atteso; ora avanzato, vedi §2) |
| CI #504 (`0b838a1`) | verde | ✔ `https://github.com/umikasaiii/Jarvis-/actions/runs/42425489818` |
| Migrazioni emulatore #9 (`0b838a1`) | verde | ✔ `https://github.com/umikasaiii/Jarvis-/actions/runs/42425489830` (10/10 test eseguiti) |
| Release `latest-debug` | target `550ca02` | ✔ **non modificata** (ricontrollata via API: pubblicata 2026-10-06, `target_commitish 550ca02`, SHA-256 APK `3b718689…dbf49`); su tutte le run di questo step il job `publish` è **skipped** |
| Branch canonico | non toccato | ✔ nessun push/merge |

## 2. Build selezionata

Non ho usato l'APK di `0b838a1`, perché leggendo `BackupRepository.collectSources()` ho trovato una lacuna di protezione dati (§5) che ho corretto in modo mirato. La build candidata è la **successiva, completamente validata**:

| Voce | Valore |
|---|---|
| **Commit** | `846cab0c63d0447a59a1906379a06fa9a1fc3154` (branch `claude/step02-db-hardening-validation`) |
| **CI** | `https://github.com/umikasaiii/Jarvis-/actions/runs/42430745363` (CI #508, tutti i job verdi) |
| **APK (artefatto GitHub Actions `app-debug`)** | `https://github.com/umikasaiii/Jarvis-/actions/runs/42430745363/artifacts/13597414500` (scadenza 2027-02-06; serve il login GitHub) |
| **SHA-256 dell'APK** | `7511ebbc94b1152135cb514f90ad308591ec76946d8ec4c378dba07533ced49f` (216 901 763 byte) |
| SHA-256 dello zip artefatto (digest di GitHub) | `6b8006e788d7e7a3963f9549f2131789249355e2455d3bbd2d906af9831454ed` |
| **APK isolato per test sicuri** (`app-debug-isolated`) | `https://github.com/umikasaiii/Jarvis-/actions/runs/42430745363/artifacts/13597910753` — applicationId `com.simone.jarvismobile.debug.beta` (il suo `.sha256` è nello zip) |
| Report identità APK | artefatto `apk-report` (id 13596944489) della stessa run |

Fallback verificato ma **senza** la correzione del backup: APK di `0b838a1` = artefatto `app-debug` della CI #504 (`…/runs/42425489818/artifacts/13595049991`, SHA-256 nel log "Compute APK SHA-256" di quella run). Non consigliato.

Commit successivi a `846cab0` contengono solo documentazione: le loro run di CI producono altri artefatti con altri SHA-256 — **ignorali**, l'APK da testare è solo quello del §2.

**Mai pubblicati su `latest-debug`.** Sono artefatti di workflow: ogni APK è legato al proprio commit (BUILD_ID nell'app = prime cifre del commit, visibile in Diagnostica).

## 3. Esito delle verifiche automatizzate (sul commit `846cab0`)

| Verifica | Esito | Livello |
|---|---|---|
| Guard di protezione release (R1–R6 + 7 mutazioni) | ✔ | eseguito in CI |
| Test `:core` | ✔ (1690 test) | TESTATO |
| `assembleDebug` | ✔ | COMPILATO |
| Test unitari Android (inclusi `JarvisMigrationsTest`, `PremigrationSnapshotterTest`, **nuovo** `BackupCoversPrivateUserFilesTest`) | ✔ | TESTATO |
| Schema Room esportato = `15.json` committato | ✔ | verificato |
| Compilazione `androidTest`, lint, packaging AEC3 (solo `arm64-v8a`) | ✔ | COMPILATO |
| APK identità/compatibilità (`apk-report`) | ✔ vedi sotto | verificato sul file reale |
| APK isolato (`debug.beta`) costruito e riconosciuto | ✔ | verificato sul file reale |
| Test migrazioni su emulatore (v1…v14 → 15) | ✔ **su `0b838a1`**; i file DB/migrazioni/schemi sono **identici** in `846cab0` (`git diff` senza differenze) | TESTATO (non rieseguito su `846cab0`: il workflow si avvia solo se cambiano quei file) |
| **Test su Honor 200** | **PENDING** — nessuno eseguito | — |

**Identità dell'APK `846cab0` (letta con `aapt2`/`apksigner` dal file reale, confrontata con il `latest-debug` pubblicato):**

| Controllo | Risultato |
|---|---|
| Application ID | `com.simone.jarvismobile.debug` — **identico** a `latest-debug` |
| Firma | verificata (schema v2); certificato `CN=Android Debug`, SHA-256 `33018d7f10c4a1aa07cc1e609ba541caf1cd318d7e670556f9c826d29b07c8fb` — **identico** a `latest-debug` **e** al `app/debug.keystore` committato → l'aggiornamento in-place è consentito da Android |
| versionCode / versionName | 1 / 0.1.0 (uguale al pubblicato: Android lo accetta come aggiornamento, non è un downgrade) |
| minSdk / targetSdk / compileSdk | 31 / 35 / 35 |
| ABI | solo `arm64-v8a` (HONOR 200 è arm64) |
| `debuggable` | sì (build di debug; serve alla copia grezza dei dati) |
| Permessi | **29, identici a `latest-debug`** (nessuno aggiunto o rimosso; incluso microfono, notifiche, posizione anche in background, allarmi esatti, `SYSTEM_ALERT_WINDOW`, Health Connect). Nessun `MANAGE_EXTERNAL_STORAGE`, nessun accessibility |
| Modifiche distruttive | nessun `fallbackToDestructiveMigration` su `jarvis.db`; versione DB resta 15 |

**Compatibilità Android 16 / HONOR 200 — NON VERIFICATA.** Posso dire solo che `minSdk 31` ≤ Android 16 e `targetSdk 35` (Android 15): su Android 16 l'app gira con i comportamenti di compatibilità per target <36. Nessun test su dispositivo, emulatore Android 16 o MagicOS è stato eseguito.

## 4. Protezione dei dati prima dell'installazione

### 4.1 Cosa il backup dentro l'app **non** copre (verificato nel codice)
L'app che hai oggi installata (`latest-debug`, `550ca02`) esegue un backup cifrato che contiene **solo**: database Room (`jarvis.db`), impostazioni (DataStore), note del vault Obsidian se configurato, e i file importati in `documents/`. **Non contiene** i file privati dell'app: `memoria.md` (Memoria, «solo archivio locale»), `agenda.md` e `automazioni.md` (copie locali, uniche se non usi un vault), `chat.json`, `conversation_memory_v2.json`, `note_backgrounds/`. Anche `models/`, `navigation/`, `knowledge/` sono solo elencati, non copiati (si reimportano).
La build candidata **li include** (correzione in questo step), ma il backup **prima** dell'aggiornamento lo fa ancora l'app vecchia. Per questo serve la **copia grezza** (4.3), che non dipende dall'app.

### 4.2 Rollback e downgrade — cosa è vero e cosa no
* Conservare il vecchio APK **non** garantisce il rollback dei dati: dopo una migrazione, un database di versione maggiore **non** torna indietro.
* La versione DB della build candidata è **15**. Lo stato del tuo telefono lo leggi nel report del §4.3 (`db_user_version`).
  * Se è già **15** (build `550ca02`): nessuna migrazione; reinstallare `latest-debug` sopra non cambia lo schema. Le migrazioni 13→14/14→15 corrette sono idempotenti.
  * Se è **<15**: l'aggiornamento migra a 15. **Non installare mai** una build più vecchia di `550ca02` dopo: le build vecchie hanno ancora il fallback distruttivo e **cancellerebbero** il database. In quel caso il recupero passa **solo** dalla copia grezza/backup.
* Non proporre né eseguire downgrade. Non disinstallare mai l'app: cancella tutti i dati.

### 4.3 Procedura (tutto sul PC; sul telefono non viene scritto nulla dalla copia grezza)
Prerequisiti: PC con `adb` (Platform-Tools) e Python 3, telefono con «Debug USB» attivo e PC autorizzato; l'app installata è la build **debug** (`com.simone.jarvismobile.debug`).

1. **Fotografa lo stato** (screenshot): Dashboard (conteggio «Archivio locale»), Memoria, Agenda/Attività, Automazioni › Regole avanzate, Impostazioni › Modelli (modello caricato), Diagnostica (BUILD_ID). Annota i numeri.
2. **Copia grezza di tutti i dati privati** dal PC (il telefono resta com'è):
   * Windows: apri **cmd.exe** (non PowerShell: corrompe i file binari) nella cartella `tools\beta` ed esegui `raw_backup.cmd`.
   * Linux/macOS/Git Bash: `bash tools/beta/raw_backup.sh`.
   Crea `jarvis-raw-AAAAMMGG-HHMM.tar` e ne stampa SHA-256. (Se `run-as` fallisce — alcune ROM lo limitano — **fermati**: non puoi autorizzare l'aggiornamento principale con questa via; vedi 4.5.)
3. **Verifica che sia leggibile e completa** (lavora su una copia temporanea, non tocca né il file né il telefono):
   `python tools/beta/verify_raw_backup.py jarvis-raw-….tar --json prima.json`
   Deve stampare `RESULT: OK` (integrità SQLite `ok`, `db_user_version`, righe per tabella: appunti/da-vedere, liste, documenti, automazioni, luoghi, coda AI, memoria conversazionale…, record di `memoria.md`/`agenda.md`/`automazioni.md`, file di documenti, voci di chat, impostazioni presenti). **Confronta con gli screenshot del punto 1**: i numeri devono corrispondere.
4. **Backup cifrato dell'app** (copia aggiuntiva): Impostazioni › «Backup e sincronizzazione» → «Scegli cartella» (una cartella **fuori** dall'archiviazione privata, es. `Download/JARVIS-Backups`) → «Mostra la chiave di recupero» e **salvala nel gestore password** (senza chiave il backup resta illeggibile) → «Esegui backup ora» → sul backup elencato «Verifica» → deve dire «Integrità verificata: OK».
5. **Metti le copie fuori dal telefono**: copia sul PC la cartella `JARVIS-Backups` (`adb pull /sdcard/Download/JARVIS-Backups .`), il `.tar` e `prima.json`; controlla che gli SHA-256 coincidano; tieni una **seconda copia** su un altro supporto (USB/cloud personale).
6. **GO / NO-GO.** Puoi aggiornare l'installazione principale **solo se** TUTTI sono veri; altrimenti **NO-GO**:
   - [ ] `verify_raw_backup.py` → `RESULT: OK` e conteggi coerenti con gli screenshot
   - [ ] il `.tar` è sul PC **e** su un secondo supporto, SHA-256 verificato
   - [ ] backup in-app creato e «Verifica» = OK; chiave di recupero salvata
   - [ ] hai letto il §4.2 (niente disinstallazione, niente build più vecchie)
   - [ ] hai la procedura di recupero (4.4) a portata di mano
   Se non riesci a ottenere una copia recuperabile: **NON** aggiornare l'installazione principale; usa solo l'APK isolato (§6).

### 4.4 Procedura di recupero (se qualcosa va storto) — NON PROVATA su telefono
Regola: **non usare l'app**, non disinstallare, non cancellare i dati.
* **A. L'app non parte con un messaggio di database** (`UnsupportedDatabaseVersionException` o «copia di sicurezza… non riuscita»): il file è **intatto** (verificato dai test). Se è «spazio insufficiente»: libera spazio e riavvia. Se è «più recente»: stai installando una build più vecchia: reinstalla la build corretta (`adb install -r`).
* **B. Dati mancanti o strani dopo l'aggiornamento**: ferma l'app (`adb shell am force-stop com.simone.jarvismobile.debug`), esegui di nuovo `raw_backup` (conserva lo stato attuale), poi **ripristina la copia grezza**:
  ```
  adb push jarvis-raw-AAAAMMGG-HHMM.tar /data/local/tmp/jarvis-restore.tar
  adb shell chmod 644 /data/local/tmp/jarvis-restore.tar
  adb shell am force-stop com.simone.jarvismobile.debug
  adb shell "run-as com.simone.jarvismobile.debug tar -xf /data/local/tmp/jarvis-restore.tar"
  adb shell rm /data/local/tmp/jarvis-restore.tar
  ```
  (sovrascrive `databases/`, `files/`, `shared_prefs/` con la copia; poi riapri l'app e riesegui `raw_backup` + `verify_raw_backup.py` per confrontare i conteggi). **Questa procedura non è stata provata su un telefono reale**: provala prima sull'APK isolato (§6, prova R).
* **C. Ripristino dal backup dell'app**: Backup › «Ripristina» sul backup scelto; l'app crea prima un backup di sicurezza dello stato attuale; riavvia l'app dopo il messaggio. Con la chiave di recupero si può leggere su un'installazione isolata.
* Gli snapshot automatici pre-migrazione, se creati, sono in `databases/premigration/` e sono **già inclusi** nella copia grezza.

### 4.5 Se `run-as` non funziona sull'HONOR 200
Non posso garantire una copia completa dei file privati con la sola app vecchia. Allora: **no** aggiornamento in-place principale. Opzioni: (1) provare la build su un secondo profilo/telefono o sull'**APK isolato**; (2) aggiornare solo dopo che hai esportato a mano ciò che ti serve (Memoria, Agenda, Automazioni) — oppure (3) dimmelo e valutiamo un percorso alternativo (es. esportazione dedicata nell'app, nuova modifica + CI).

## 5. Problema trovato e corretto in questo step
**Lacuna del backup (bloccante per la protezione dati)**: i file privati elencati in §4.1 non erano in nessun backup. Correzione mirata: `BackupRepository.collectSources()` ora li copia (`BackupPrivateFiles`), stessa mappa `relPath → filesDir` già usata per `documents/` → **formato di archivio invariato**, ripristino con la logica esistente (path-safety e staging atomico). Un test (`BackupCoversPrivateUserFilesTest`) fissa i nomi alle costanti dei repository che li scrivono, così un rinomina futuro non li esclude di nuovo in silenzio. **Limite onesto**: non provato con un backup→ripristino reale su emulatore/telefono, e non cambia ciò che l'app *vecchia* include.
Trovato anche: il mio primo rapporto APK confrontava due impronte di certificato vuote e dava OK; corretto (ora fallisce se la firma non è leggibile) e rieseguito: le impronte reali coincidono.

## 6. Installazione — istruzioni esatte
**Non installo nulla io.** Solo dopo il GO del §4.3.

1. Scarica lo zip dell'artefatto `app-debug` (link §2, serve il login GitHub), estrai `app-debug.apk`.
2. Verifica l'impronta: Windows `certutil -hashfile app-debug.apk SHA256` → deve essere `7511ebbc94b1152135cb514f90ad308591ec76946d8ec4c378dba07533ced49f`. Se differisce: **fermati**.
3. Installa **sopra** l'app esistente, senza disinstallare: `adb install -r app-debug.apk` (mostra l'errore in chiaro). **Mai** `adb uninstall`, mai `-d`, mai «cancella dati».
4. Apri l'app; in Diagnostica controlla che il BUILD_ID inizi con `846cab0`.
5. Riesegui `raw_backup` e `verify_raw_backup.py --json dopo.json`; confronta con `prima.json` (stessi conteggi; `db_user_version` 15).

**Installazione isolata (rischio zero per i tuoi dati reali):** scarica `app-debug-isolated` (link §2). Ha un altro applicationId (`…debug.beta`), dati propri, **stessa firma**: si installa **accanto** all'app vera. Va configurata da zero (permessi, import del modello `.litertlm`, ecc.). Per la copia grezza di questa istanza: `raw_backup.sh com.simone.jarvismobile.debug.beta`.

## 7. Checklist di test (usa `docs/ANDROID_OFFLINE_STEP02_HONOR200_CHECKLIST.md` per i passaggi dettagliati)
Stato di partenza: **tutti PENDING**. Segna tu PASS/FAIL solo dopo la prova sul telefono.

| # | Prova | Dove eseguirla senza rischiare i dati | Stato |
|---|---|---|---|
| 1 | Avvio a freddo | **Isolata** o principale | PENDING |
| 2 | Aggiornamento in-place senza perdita di dati | **Solo principale**, solo dopo GO §4.3 (confronto `prima/dopo.json`) | PENDING |
| 3 | Note e archivio personale | Isolata (crea dati di prova) / principale (verifica) | PENDING |
| 4 | Attività, scadenze, promemoria | Isolata | PENDING |
| 5 | Automazioni | Isolata | PENDING |
| 6 | Caricamento AI locale (import `.litertlm`) | Isolata (poi principale) | PENDING |
| 7 | Chat in modalità aereo, senza Core PC (Core è spento di default) | Isolata | PENDING |
| 8 | Stop e riavvio immediato dell'assistente (orb ×5, «ok» da solo) | Isolata | PENDING |
| 9 | Background e schermo spento (nessun microfono a schermo spento; promemoria esatto) | Isolata | PENDING |
| 10 | Notifiche e briefing mattutino (3 mattine consecutive) | Principale | **PENDING (più giorni)** |
| 11 | Backup e ripristino **su installazione isolata** | **Solo isolata** (R: provare anche la procedura §4.4 B) | PENDING |
| 12 | Nessun crash/azione distruttiva durante l'aggiornamento | Principale (log `adb logcat`) | PENDING |

**Test che puoi fare subito senza rischiare i dati personali:** 1, 3, 4, 5, 6, 7, 8, 9, 11 sull'**APK isolato**; la copia grezza e `verify_raw_backup.py` sull'app vera (sono di sola lettura sul telefono). **Da non fare finché non hai il GO:** 2 e 12 sull'installazione principale, e qualunque ripristino sull'app vera.

## 8. Problemi ancora aperti
1. **Nessuna verifica su Honor 200** (P0-2 del PASSAGGIO 2 aperto).
2. **Android 16 non verificato**; targetSdk 35.
3. Il backup eseguito **prima** dell'aggiornamento è fatto dall'app vecchia (incompleto): la copia grezza è obbligatoria. `run-as` su MagicOS non è verificato.
4. La procedura di ripristino della copia grezza (§4.4 B) **non è stata provata** su un telefono.
5. Il test di migrazione su emulatore non è stato rieseguito su `846cab0` (file DB invariati).
6. `versionCode` resta 1: l'app non mostra «nuova versione»; riconosci la build dal BUILD_ID.
7. L'APK si scarica come artefatto GitHub Actions (login richiesto, scadenza 2027-02-06), non da una release pubblica (voluto: `latest-debug` intatto).
8. Briefing mattutino su MagicOS: ancora inaffidabile secondo l'evidenza storica (P1-2 del PASSAGGIO 1); prova 10 resta PENDING.
9. Il backup non include `models/`, `navigation/`, `knowledge/` (si reimportano).

Non iniziato BETA 2/8. Nessun merge sul branch canonico; `latest-debug` non toccata.
