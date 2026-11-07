# JARVIS Android — PASSAGGIO 2/32 — Protezione dei dati, migrazioni Room, validazione CI

**Stato finale: CI VERIFIED** per P0-1 (database/migrazioni/protezione release). **P0-2 resta APERTO**: nessuna verifica su Honor 200 è stata eseguita (non è DEVICE VERIFIED).
Distinzione usata ovunque: COMPILATO ≠ TESTATO ≠ VERIFICATO SU DISPOSITIVO.

---

## 1. Baseline e branch

| Voce | Valore |
|---|---|
| Repository | `umikasaiii/Jarvis-` (nessun'altra repository toccata) |
| Branch canonico | `claude/jarvis-mobile-automazioni-dashboard-b4xa7e` @ `550ca02` — **non modificato, nessun push, nessun merge** |
| Branch di verifica (unico branch pushato) | `claude/step02-db-hardening-validation` |
| Ultimo commit verificato | `0b838a199b33a4711b1eabdbb5c5d1bb0806c7e7` (+ questo aggiornamento del report) |
| Release `latest-debug` | **non toccata**: verificato via API dopo tutte le esecuzioni — `target_commitish 550ca02`, pubblicata il 2026-10-06 |
| Clone originale sul branch vecchio | non utilizzato |
| Dati Android | nessun dato reale cancellato o reinstallato (nessun dispositivo coinvolto) |

## 2. Protezione della pubblicazione APK (`latest-debug`)

**Causa**: `ci.yml` partiva su `push` di `claude/**` e su qualunque `workflow_dispatch`, e lo step di pubblicazione (nello stesso job, token `contents: write`) eseguiva `gh release delete latest-debug --cleanup-tag` + `gh release create`. Qualunque branch di verifica o run manuale poteva quindi sostituire la release condivisa.

**Correzione (verificata nel codice del workflow prima del push):**
* Job `build` (compila/testa/carica artefatti): `permissions: contents: read` → il suo token **non può** creare/modificare/cancellare release o tag.
* Pubblicazione spostata nel job separato `publish` (`needs: build`, `contents: write`) con condizione **esatta**: `github.repository == 'umikasaiii/Jarvis-'` **e** `github.ref == 'refs/heads/claude/jarvis-mobile-automazioni-dashboard-b4xa7e'` **e** (`workflow_dispatch` oppure `push` classificato APK-impattante). Scarica l'APK costruito dal `build` della stessa run; non ricostruisce nulla.
* Variabile `PUBLISH_REF` a livello di workflow; nessun altro workflow (`aec3-native`, `db-migration-tests`, `room-historical-schemas`) ha `contents: write` o comandi di rilascio; tutti dichiarano `permissions: contents: read`.
* **Verifica automatica**: `.github/scripts/ci_publish_guard.py` (regole R1–R6: comandi di release/tag solo nel job `publish` di `ci.yml`; gate esatto su repository + ref; nessun `contents: write` altrove; nessun `pull_request_target`; `PUBLISH_REF` = ramo canonico; `publish` dipende da `build`) e `.github/scripts/test-ci-publish-guard.sh` che lo esegue **e** lo mette alla prova con 7 mutazioni (gate rimosso, gate allentato a `claude/**`, `release delete` nel job build, `contents: write` nel build, mutazione in un workflow di test, `pull_request_target`, `publish` senza `needs`): il guard deve fallire su tutte. Lo step "Verify release-publish guard" è il primo step del job `build` di ogni run.
* **Esito osservato in CI**: su ogni run del branch di verifica (#496–#504) il job `Publish latest-debug (canonical branch only)` è risultato **skipped**; la release non è cambiata (§1). Nessun test elimina release, tag o artefatti.

## 3. Causa dei problemi database (invariata dal primo report)

* `jarvis.db` aveva `fallbackToDestructiveMigration()`: ogni `version++` senza migrazione, o un file di versione <3, **azzerava in silenzio** il database.
* `exportSchema = false`: nessuno schema versionato → nessun `MigrationTestHelper`.
* Solo 2 test di migrazione, solo compilati. Un secondo database, `weather_decisions.db`, ha ancora il fallback distruttivo: **scelta mantenuta** (ricevute meteo riderivabili, escluse dal backup).

## 4. Modifiche (file)

| File | Modifica |
|---|---|
| `di/DatabaseModule.kt` | **rimosso** `fallbackToDestructiveMigration()`; `JarvisMigrations.ALL`; `DatabaseOpenGuard.preflight()` prima del builder |
| `background/JarvisMigrations.kt` (nuovo) | registro unico 1→15; nuove migrazioni **1→2** e **2→3** (additive); `missingSteps()` |
| `background/DatabaseOpenGuard.kt` (nuovo) | classifica `user_version`; file **più nuovo** o sotto il minimo → `UnsupportedDatabaseVersionException` **senza toccare il file**; upgrade → snapshot verificato (§6) |
| `background/PremigrationSnapshotter.kt`, `AndroidDbSnapshotEngine.kt` (nuovi) | snapshot pre-migrazione consistente, verificato, **fail-closed** |
| `background/JarvisDatabase.kt` | `exportSchema = true` |
| `proactive/TriggerEvidenceMigrations.kt`, `ProactiveSchedulePlanMigrations.kt`, `responsibility/ResponsibilityMigrations.kt` | **correzione di un difetto reale** trovato dagli schemi autentici (§5) |
| `backup/BackupRepository.kt` | `MIN_SUPPORTED_DB_SCHEMA_VERSION` = `JarvisMigrations.OLDEST_SUPPORTED_VERSION` (1). Formato di backup **invariato** |
| `automation/rule/RuleMigrations.kt` | solo commento |
| `app/build.gradle.kts`, `gradle/libs.versions.toml` | arg KSP `room.schemaLocation`, asset `androidTest`, `room-testing`, `-Pjarvis.emulatorAbi` (solo CI emulatore) |
| `app/schemas/.../1.json … 15.json` | 15 schemi Room (§7) |
| Test | `JarvisMigrationsTest`, `PremigrationSnapshotterTest` (JVM); `JarvisMigrationInstrumentedTest` (emulatore) |
| CI | `ci.yml` (guard, job `publish`, verifica schema, artefatto), `db-migration-tests.yml`, `room-historical-schemas.yml`, `.github/scripts/ci_publish_guard.py`, `test-ci-publish-guard.sh`, `tools/db/export_historical_schemas.sh` |

Nessuna funzionalità rimossa; nessun secondo database/store; Core PC non toccato; il versionamento dello schema resta 15.

## 5. Difetto reale trovato dalla validazione con schemi autentici

Eseguendo su emulatore le migrazioni a partire dagli schemi **autentici** (generati dal codice originale di ogni versione), la migrazione fino a v15 è fallita **solo** partendo da v13 e v14:
`IllegalStateException: Migration didn't properly handle: trigger_evidence` — nella tabella trovata mancavano i due indici (`source`, `eventAtMs`) che l'entity attuale dichiara.

Causa: prima della correzione E.1 l'entity `TriggerEvidenceRowEntity` non dichiarava indici; un database **creato da un'installazione pulita** di una build v13/v14 di allora non ha quegli indici, e nessuna migrazione 13→14 / 14→15 li aggiungeva. I database arrivati a v13 tramite la migrazione 12→13 li hanno già, quindi il problema non emergeva con i test sintetici né sulle installazioni "in-place" da v12. Dopo l'upgrade alla build attuale quei dispositivi avrebbero avuto Room che rifiuta il database all'apertura (crash all'avvio — stessa famiglia di E.1).

Correzione: `TriggerEvidenceMigrations.ensureIndices(db)` (`CREATE INDEX IF NOT EXISTS`, idempotente) chiamata da 12→13, 13→14 e 14→15. Nessun effetto dove gli indici esistono già. Un test JVM (`migrationsFrom13And14RepairTriggerEvidenceIndices`) ne presidia la presenza. **Dopo la correzione tutti i percorsi da v1…v14 a v15 passano su emulatore.**

Questo è un caso in cui "il codice compila e le migrazioni esistono" **non** bastava a dichiarare sicuro il percorso: serviva eseguirlo contro lo schema reale.

## 6. Snapshot pre-migrazione, WAL e fail-closed

Analisi della prima versione: copiava solo `jarvis.db` dopo un `wal_checkpoint` non verificato, scriveva direttamente il nome finale (un'interruzione lasciava un file parziale con nome "valido"), non verificava la copia, e un errore veniva solo loggato. **Non era accettabile.**

Versione finale (`PremigrationSnapshotter`):
1. **Consistenza/WAL**: `VACUUM INTO` (legge attraverso il WAL e produce un file autonomo). Fallback: `wal_checkpoint(TRUNCATE)` che **deve** riportare "non busy" e WAL vuoto, poi copia.
2. **Verificata prima di essere valida**: scritta come `*.part`; `PRAGMA quick_check == ok`, `user_version == versione di origine`, **conteggi riga per ogni tabella identici** all'originale; solo dopo `rename` atomico al nome finale. Un `.part` non è mai uno snapshot (anche dopo kill del processo: viene cancellato al tentativo successivo).
3. **Mai sovrascrive una copia buona**: nome univoco con timestamp, nessun overwrite, la potatura (ultime 2) avviene **solo dopo** che la nuova copia è verificata; uno snapshot già verificato e più recente del file viene riusato invece di ricopiare.
4. **Spazio/permessi/interruzioni**: controllo spazio (`dimensione × 1,2 + 8 MiB`) prima di scrivere; qualunque errore IO elimina il `.part`.
5. **Decisione esplicita: si blocca.** Se lo snapshot non può essere creato e verificato, `SnapshotFailedException` interrompe l'apertura: Room non esegue la migrazione, **l'originale resta intatto** (al massimo un checkpoint del WAL), il messaggio in italiano spiega cosa fare (liberare spazio e riavviare). Motivazione: un upgrade è raro, i dati sono insostituibili, e un'app che spiega "libera spazio" è preferibile a una che migra senza rete di sicurezza. Se il database originale non supera `quick_check` non viene né copiato né migrato.
6. **Non è un secondo backup**: copia transitoria per un solo upgrade in `databases/premigration/`; `BackupRepository` non la legge e il formato di backup non cambia.

Prove: 10 test JVM con motore finto (percorso felice, scrittura interrotta, copia incompleta/integrità/versione errata, non sostituisce né pota una copia buona dopo un tentativo fallito, spazio insufficiente prima di scrivere, riuso, originale corrotto, `.part` residui, potatura, versione cambiata) e, **su emulatore con SQLite reale**: dati ancora nel WAL (connessione aperta, riga non ancora nel file principale) presenti nello snapshot; snapshot impossibile (path occupato da un file) → upgrade bloccato e database **byte-identico**; snapshot di v1 con `user_version=1` e riga conservata.

## 7. Schemi Room

* `exportSchema = true`, `ksp { arg("room.schemaLocation", …/app/schemas) }`, asset `androidTest`.
* **15.json** — esportato da Room in CI, committato **byte-identico** (sha256 `c7eb635b1e6dff2aabc0e8743075671ff590e802e958467c9e1aa0378e60121d`); lo step CI "Verify exported Room schema" fallisce se manca o se differisce da quello generato.
* **1.json … 14.json — AUTENTICI e disponibili tutti.** Rigenerati da Room/KSP in CI dal codice del commit che ha rilasciato ciascuna versione (v1 `9396632`, v2 `a52720f`, v3 `6054522`, v4 `4eba556`, v5 `b6bd503`, v6 `0a0f426`, v7 `9d59e3f`, v8 `e8cc2d4`, v9 `e1982cf`, v10 `60009a5`, v11 `c0c9eea`, v12 `e9b93ed`, v13 `7d527d5`, v14 `15dbea3`). Ogni file è stato verificato: sha256 stampato dal job = sha256 ricalcolato, campo `database.version` = nome file, numero di entity coerente con la storia (v1: 1, v2: 3, v3: 6, … v14: 18). **Nessuno schema è stato scritto a mano o dedotto.**
* Nota onesta sulla rigenerazione: per v1 e v5/v6 lo script ha dovuto (a) creare un archivio `.aar` **vuoto** segnaposto per le dipendenze binarie mai committate (`app/libs/sherpa-onnx-*.aar`) e (b) ritentare con KSP1 (`-Pksp.useKSP2=false`) dove KSP2 falliva con "unexpected jvm signature V". Questo non influenza lo schema, che Room deriva dalle sole `@Entity`/`@Database`.
* Rigenerazione e confronto byte-per-byte con i file committati: workflow manuale `Room historical schemas`.
* Il test JVM `committedSchemasCoverEveryVersionAndMatchTheirFilename` e quello strumentato `everyShippedSchemaVersionIsCommitted` falliscono se manca una qualunque versione.
* Versioni **NON VERIFICATE**: nessuna. Resta fuori scopo qualunque schema precedente alla v1 (non esiste).

## 8. Migrazioni realmente testate (emulatore, SQLite reale)

Workflow `DB migration tests (emulator)` — Android 14 (API 34, x86_64), 10 test strumentati **eseguiti** (non solo compilati), tutti passati sull'ultimo commit:

| Test | Cosa dimostra |
|---|---|
| `everyAvailableOlderSchemaMigratesToCurrentWithoutLosingData` | per **ogni** v1…v14: crea un database reale dallo schema autentico con `MigrationTestHelper`, **semina una riga in ogni tabella** (valori derivati da `PRAGMA table_info`, tabelle FTS incluse), migra a v15 con il registro completo, validazione Room (colonne, indici, vincoli) e verifica riga-per-riga/valore-per-valore che nulla sia perso o alterato; verifica che tutte le 14 versioni siano state esercitate |
| `version1…`/`version2UpgradesThroughTheWholeChain…` | database v1/v2 da SQL grezzo (stesse tabelle di allora) aperti con il vero `JarvisDatabase` **senza fallback distruttivo**: righe utente conservate, nuove tabelle presenti |
| `currentSchemaIsExportedAndOpens`, `everyShippedSchemaVersionIsCommitted`, `aFreshInstallCreatesTheCurrentSchema` | schema 15 esportato, copertura schemi completa, installazione pulita |
| `aNewerDatabaseIsRefusedAndLeftByteForByteUntouched` | file di versione più recente rifiutato, **sha256 invariato**, nessuno snapshot |
| `snapshotContainsDataStillSittingInTheWalAndIsVerified`, `ifTheSnapshotCannotBeMade…`, `upgradeLeavesAPreMigrationSnapshot…` | §6 |

Relazioni: nessuna `FOREIGN KEY` è dichiarata nello schema (verificato nei JSON); indici verificati dalla validazione Room.

Limite onesto: i dati seminati sono sintetici ma strutturalmente completi (una riga per tabella): non coprono volumi o contenuti reali; non sostituiscono la prova sul telefono.

## 9. Backup e ripristino

Verificato per ispezione del codice + test: formato di backup invariato; `restore()` continua a fare staging e cutover al cold start; `RestoreSanitizeCallback` resta in `onOpen`, dopo le migrazioni; il limite minimo accettato ora è 1 (prima 3) perché 1–2 sono migrabili davvero; un database ripristinato di versione <15 passa da `preflight` e snapshot come un upgrade normale; un backup di versione **più recente** è rifiutato senza modifiche. L'interruzione a metà di una migrazione è coperta da Room (una transazione per migrazione) e dallo snapshot. **Non** è stata eseguita una prova end-to-end backup→ripristino→migrazione su emulatore/dispositivo (resta nel protocollo Honor 200).

## 10. Risultati CI (branch di verifica)

Link: `https://github.com/umikasaiii/Jarvis-/actions/runs/<id>`

| Commit | CI (`ci.yml`) | Emulatore (`db-migration-tests`) | Note |
|---|---|---|---|
| `2b7a37c` | #496 `42414260859` **fail** (2 test JVM errati miei) | #1 `42414260937` **fail** (compile androidTest) | errori nei miei test, corretti |
| `4af8122` | #497 `42415129857` ✔ | #2 `42415129899` ✔ 10/10 | ✔ ma storici non ancora presenti (test "vuoto") — non considerato valido |
| `9a399fa` | #498 `42416643283` ✔ | #3 `42416643251` ✘ | schemi autentici in uso → difetto §5 trovato; v1/v5/v6 non ricostruibili finché lo script non è stato corretto |
| `1f50fc7` | #499 `42418024230` ✔ | #4 `42418024255` ✘ | 14/14 schemi disponibili; fallimento da v13/v14 |
| `aa36713` | #500 `42419363375` ✔ | #5 `42419363409` ✘ | messaggio completo del difetto; un job storico (v7) intermittente |
| `c4d4f5e` | #501 `42420818894` ✔ | #6 `42420818916` ✔ 10/10 | **correzione §5**: tutti i percorsi 1→15 passano |
| `f93d833` | #502 `42422406750` ✔ | #7 `42422406768` ✔ | `15.json` committato = generato |
| `1e383ae` | #503 `42423990216` ✔ | #8 `42423990217` ✔ | |
| `0b838a1` | #504 `42425489818` ✔ | #9 `42425489830` ✔ 10/10 | **schemi 1–15 committati**, test su set committato |

Contenuto di una run `ci.yml` verde: guard release, validazione wrapper, build nativa AEC3, test `:core` (**1690 test, 0 falliti**), `assembleDebug`, test unitari Android (≈390 test, incl. `JarvisMigrationsTest` e `PremigrationSnapshotterTest`; il run #496 ne contava 389 con 2 falliti per errori nei miei test; verdi da #497 in poi), verifica schema esportato, compilazione `androidTest`, lint, packaging AEC3, upload APK come **artefatto di workflow** (non release), job Windows PS 5.1; job `publish` **skipped**.

## 11. Rischi residui

1. **Nessuna verifica su Honor 200** (P0-2 aperto). I test usano dati sintetici su emulatore x86_64.
2. Una build **più vecchia** installata su un database più nuovo **non parte** (eccezione esplicita, file intatto); non c'è schermata di recupero.
3. Lo snapshot fail-closed può bloccare l'avvio dell'upgrade con poco spazio: scelta deliberata, messaggio esplicito; l'unica uscita è liberare spazio.
4. Gli snapshot in `databases/premigration/` non hanno UI di ripristino (recupero manuale) e non sono nel backup.
5. `weather_decisions.db` mantiene il fallback distruttivo (documentato).
6. Dati fuori da Room (vault Markdown, DataStore) non coperti da questo step.
7. I job emulatore girano su x86_64 con `-Pjarvis.emulatorAbi`; l'APK distribuito resta arm64-v8a.
8. Le migrazioni 13→14 e 14→15 sono state modificate **in modo idempotente**; per database già a v15 non cambia nulla.

## 12. Idoneità all'aggiornamento in-place sull'Honor 200

**Idoneo a un tentativo controllato, NON ancora dimostrato sul dispositivo.** Dal lato dati: tutti i percorsi 1→15 sono stati eseguiti con schemi autentici, i dati seminati sono conservati, il database non viene mai ricreato, uno snapshot verificato precede l'upgrade, un database incompatibile è rifiutato senza modifiche. Prima del tentativo: backup cifrato + copia dell'APK attuale (vedi checklist) e installazione **soltanto** da build prodotta dal branch di verifica o da un futuro merge autorizzato — **`latest-debug` non contiene queste modifiche**.

## 13. Condizioni

* **P0-1 → RISOLTO (CI VERIFIED)**: (1) CI verde sul commit con le modifiche ✔; (2) `15.json` committato e identico al generato ✔; (3) schemi 1–14 autentici committati ✔; (4) test migrazioni **eseguiti** su emulatore e verdi ✔; (5) nessun `fallbackToDestructiveMigration` su `jarvis.db` ✔ (test JVM).
* **P0-2 → APERTO**: servono le prove fisiche su Honor 200 (`docs/ANDROID_OFFLINE_STEP02_HONOR200_CHECKLIST.md`): minimo 1, 2, 3, 4, 5, 11, 12 con esito PASS documentato. Non è stato dichiarato nulla come DEVICE VERIFIED.

## 14. Protocollo Honor 200

Vedi `docs/ANDROID_OFFLINE_STEP02_HONOR200_CHECKLIST.md` (12 prove). Nessuna è stata eseguita. Aggiunta rilevante dopo §5: la prova 2 va eseguita **anche** da una build che sia stata installata "pulita" a versione 13/14, se disponibile, perché è il caso che avrebbe fallito.
