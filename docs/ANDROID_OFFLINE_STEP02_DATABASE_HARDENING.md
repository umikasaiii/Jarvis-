# JARVIS Android — PASSAGGIO 2/32 — Protezione dei dati, migrazioni Room, baseline Honor 200

Stato finale dello step: **PARZIALE** (vedi §10–§11). Nessun commit e nessun push sono stati eseguiti.
Distinzione usata ovunque: **COMPILATO ≠ TESTATO ≠ VERIFICATO SU DISPOSITIVO**.
In questo ambiente non esistono Android SDK, emulatore né Honor 200: la parte `app/` non è stata compilata qui.

---

## 1. Baseline esatta

| Voce | Valore |
|---|---|
| Repository | `umikasaiii/Jarvis-` (solo questa) |
| Branch di riferimento | `claude/jarvis-mobile-automazioni-dashboard-b4xa7e` |
| HEAD di partenza | `550ca026b5d595dcf1f8c4d6c8a7910c875e7436` (= baseline del PASSAGGIO 1), worktree `/home/user/Jarvis-audit` (HEAD detached, creata per l'audit) |
| Working tree all'inizio | un solo file non tracciato: `docs/ANDROID_OFFLINE_STEP01_BASELINE_AUDIT.md` (conservato, non toccato da questo step) |
| Ambiente | JDK 21, Gradle wrapper; `ANDROID_HOME` assente; `dl.google.com` → 403 dal proxy (verificato); nessun emulatore/dispositivo |
| Commit/push | **nessuno** (richiesta esplicita) |

## 2. Causa dei problemi

Verificato nel codice (non solo nel report):

* `di/DatabaseModule.kt` costruiva `jarvis.db` con `.fallbackToDestructiveMigration()`. Qualunque `version++` senza migrazione, o un file di versione < 3 (nessuna migrazione esisteva sotto la 3), **ricreava il database vuoto in silenzio**: regole di automazione, luoghi, archivio/note, memoria episodica, coda risposte, responsabilità.
* `JarvisDatabase` aveva `exportSchema = false`: nessuno schema versionato, quindi nessun `MigrationTestHelper` possibile.
* Copertura: 12 migrazioni (3→4 … 14→15) registrate correttamente e contigue, ma solo 2 con un test, e **solo compilato** in CI, mai eseguito. Il crash E.1 (schema/migrazione incoerenti) era passato proprio per questo buco.
* Trovato in più (non segnalato nel PASSAGGIO 1): `BackupRepository.MIN_SUPPORTED_DB_SCHEMA_VERSION = 3` duplicava a mano il limite inferiore delle migrazioni.
* Trovato in più: esiste un **secondo database**, `weather_decisions.db` (`WeatherDecisionDatabase`, v1) con destructive fallback. **Non modificato** (vedi §6): contiene solo ricevute di valutazione meteo, riderivabili, escluse dal backup e con motivazione documentata (`WeatherDecisionDatabaseModule`).

## 3. Modifiche apportate

### Produzione (limitate alla sicurezza del DB)

| File | Modifica |
|---|---|
| `app/.../di/DatabaseModule.kt` | **Rimosso `fallbackToDestructiveMigration()`** da `jarvis.db`. Usa `JarvisMigrations.ALL`. Chiama `DatabaseOpenGuard.preflight()` prima del builder. |
| `app/.../background/JarvisMigrations.kt` (nuovo) | Registro unico 1→15. Aggiunge le migrazioni reali **1→2** (tabelle `documents`, `document_chunks` + indice) e **2→3** (`nav_places` FTS4, `nav_favorites`, `nav_history`), puramente additive; il DDL ricalca le entity, invariate da quei commit (verificato con `git log` sui file entity). Espone `CURRENT_VERSION`, `OLDEST_SUPPORTED_VERSION` e `missingSteps()`. |
| `app/.../background/DatabaseOpenGuard.kt` (nuovo) | (a) classifica `PRAGMA user_version`: file di versione **più nuova** o sotto il minimo → eccezione esplicita `UnsupportedDatabaseVersionException`, **senza toccare il file**; (b) prima di un upgrade reale crea una **copia checkpointata** in `databases/premigration/jarvis-v<N>-<ts>.db` (ultime 2; non rifatta se la copia è già più recente del file). Un errore dello snapshot è registrato e **non blocca** l'avvio. Mai loggato contenuto di tabelle. |
| `app/.../background/JarvisDatabase.kt` | `exportSchema = true` (+ commento di sincronizzazione con `CURRENT_VERSION`). |
| `app/.../backup/BackupRepository.kt` | `MIN_SUPPORTED_DB_SCHEMA_VERSION` ora = `JarvisMigrations.OLDEST_SUPPORTED_VERSION`. Formato di backup **invariato**. |
| `app/.../automation/rule/RuleMigrations.kt` | solo commento (non descrive più un fallback che non esiste). |
| `app/build.gradle.kts`, `gradle/libs.versions.toml` | `ksp { arg("room.schemaLocation", "$projectDir/schemas") }`; `app/schemas` come asset di `androidTest`; dipendenza `room-testing`; ordinamento `mergeDebugAndroidTestAssets` ← `kspDebugKotlin`; proprietà opzionale `-Pjarvis.emulatorAbi` (solo CI emulatore; l'APK resta `arm64-v8a`). |

Nessuna funzionalità rimossa; nessun secondo database o store; il Core PC non è toccato.

### Test e infrastruttura (nuovi)

| File | Contenuto |
|---|---|
| `app/src/test/.../background/JarvisMigrationsTest.kt` | JVM: catena contigua 1→15, rileva un passo mancante, ogni migrazione è un singolo passo avanti e registrata una volta, `CURRENT_VERSION` = `@Database(version)`, `exportSchema = true`, **nessun `fallbackToDestructiveMigration` in `DatabaseModule`**, arg KSP presente, logica pura di `DatabaseOpenGuard`. |
| `app/src/androidTest/.../background/JarvisMigrationInstrumentedTest.kt` | Strumentato (SQLite reale): schema esportato corrente apre; per ogni schema JSON disponibile `v < 15` crea un DB reale con `MigrationTestHelper`, **semina una riga in ogni tabella**, migra a 15 con il registro completo, valida con Room e verifica che ogni tabella/riga/valore preesistente sia intatto; catena **da v1 e da v2** da SQL grezzo (stesse tabelle di allora) aperta con il vero `JarvisDatabase` e **senza fallback distruttivo**; snapshot pre-migrazione corretto; file più nuovo rifiutato e **hash invariato**; installazione pulita. |
| `.github/workflows/ci.yml` | nuovo step "Verify exported Room schema" (fallisce se lo schema corrente non viene esportato o se lo schema committato differisce da quello generato) + upload artefatto `room-schemas`. |
| `.github/workflows/db-migration-tests.yml` | **esegue** `JarvisMigrationInstrumentedTest` su emulatore (API 34, x86_64) — separato da CI per non bloccare la pubblicazione dell'APK. |
| `.github/workflows/room-historical-schemas.yml` + `tools/db/export_historical_schemas.sh` | ricostruisce gli schemi storici 1–14 **dal codice reale** del commit che ha rilasciato ciascuna versione (verificata in locale la sola mappatura versione→commit, vedi §8). |
| `app/schemas/README.md` | regole sugli schemi (mai scritti a mano). |

## 4. Percorsi di migrazione

| Passo | Origine | Cosa fa | Test |
|---|---|---|---|
| 1→2 | `JarvisMigrations` (**nuova**) | +`documents`, `document_chunks`, indice | strumentato (raw SQL v1) — non eseguito |
| 2→3 | `JarvisMigrations` (**nuova**) | +`nav_places` (FTS4), `nav_favorites`, `nav_history` | strumentato (raw SQL v1/v2) — non eseguito |
| 3→4 | `RuleMigrations` | +4 tabelle automazioni/luoghi/esecuzioni/parcheggio | JSON v3 *(da recuperare)* |
| 4→5 | `ArchiveMigrations` | +`archive_items` | JSON v4 *(da recuperare)* |
| 5→6 | `ArchiveMigrations` | +liste e link | JSON v5 *(da recuperare)* |
| 6→7 | `EngineMemoryMigrations` | +`conversational_memory` | JSON v6 *(da recuperare)* |
| 7→8 | `ArchiveMigrations` | `archive_items` +`folder`, `pinned` | JSON v7 *(da recuperare)* |
| 8→9 | `ArchiveMigrations` | `archive_items` +`theme`, `spacing` | JSON v8 *(da recuperare)* |
| 9→10, 10→11 | `RuleMigrations` | `automation_executions` +`idempotencyKey`, +`commitState` | JSON v9/v10 *(da recuperare)* |
| 11→12 | `ProactiveOccurrenceMigrations` | +`proactive_occurrences` | JSON v11 *(da recuperare)* |
| 12→13 | `TriggerEvidenceMigrations` | +`trigger_evidence` (+2 indici; origine del crash E.1) | JSON v12 *(da recuperare)*; test E.1 preesistente (compile-only) |
| 13→14 | `ProactiveSchedulePlanMigrations` | +piano di schedulazione | JSON v13 *(da recuperare)* |
| 14→15 | `ResponsibilityMigrations` | +`agent_responsibilities`, `agent_responsibility_journal` | JSON v14 *(da recuperare)*; test preesistente (compile-only) |

Verificato staticamente: **nessuna migrazione elimina/rinomina tabelle o colonne** (`grep DROP|RENAME` → 0): tutte additive (`CREATE … IF NOT EXISTS`, `ALTER … ADD COLUMN`), quindi la conservazione dei dati è per costruzione, e il test strumentato la verifica comunque riga per riga.

## 5. Risultati dei test

| Verifica | Esito | Livello |
|---|---|---|
| `cd core && ./gradlew cleanTest test` (`:core` non modificato) | 1690 test, 0 falliti (risultato dalla build cache, vedi fondo) | **TESTATO** (JVM, locale) |
| Compilazione `app/` (`assembleDebug`) | **NON ESEGUIBILE** qui (no SDK) — da CI | non compilato localmente |
| `JarvisMigrationsTest` e gli altri test unitari `app` | **NON ESEGUITI** qui (richiedono AGP) — da CI (`testDebugUnitTest`) | scritti, non eseguiti |
| `lintDebug`, compilazione `androidTest` | **NON ESEGUIBILI** qui — da CI | — |
| `JarvisMigrationInstrumentedTest` | **NON ESEGUITO** (serve emulatore/dispositivo); workflow `db-migration-tests.yml` pronto | scritto, non eseguito |
| Schemi storici 1–14 | **NON GENERATI** (richiedono SDK/CI) | pendente |
| GitHub Actions sul commit analizzato | non rieseguibile: la CI #493 su `550ca02` era verde (PASSAGGIO 1); **le modifiche di questo step non sono state pushate, quindi non hanno alcuna evidenza CI** | — |
| Verifica logica statica | `grep` su `fallbackToDestructive` (solo `weather_decisions.db`), contiguità 1→15 e mappatura versione→commit calcolate con `git` | eseguito |

Nessun risultato è presentato come superato se non è stato eseguito.

## 6. Rischi residui

1. **Le modifiche `app/` non sono mai state compilate** (nessun SDK qui): possibili errori di compilazione/Room/KSP emergeranno solo in CI. Il passo "Verify exported Room schema" può fallire se l'export KSP non scrive in `app/schemas`.
2. **Nessuna migrazione 3→15 è ancora validata contro uno schema reale** finché gli schemi storici non sono generati e i test eseguiti. Il DDL esistente era già stato scritto a mano e provato da E.1 solo per 12→13.
3. **Nuove migrazioni 1→2 e 2→3 scritte a mano** dalle entity di allora: il DDL ricalca le entity (invariate), ma finché il test strumentato non gira non è provato che la validazione Room passi (in particolare l'FTS4 `nav_places`).
4. Un file di versione **più nuova** (downgrade) ora **blocca l'avvio** con un'eccezione chiara invece di essere cancellato. È la scelta non distruttiva voluta, ma l'app non parte finché non viene installata una build ≥ versione del file. Non esiste una schermata di recupero (fuori scope).
5. Lo **snapshot pre-migrazione** è best-effort (errore registrato, avvio non bloccato) e non è ancora esposto in UI per il ripristino: il recupero è manuale (`databases/premigration/`). Il formato di backup non lo include (non modificato, per scelta).
6. `weather_decisions.db` mantiene il destructive fallback (ricevute riderivabili; scelta già documentata). Se in futuro conterrà dati non riderivabili va trattato come `jarvis.db`.
7. Altri dati personali **fuori da Room** (vault Markdown, DataStore) non sono coperti da questo step.
8. P0-2 non è chiuso (§11).

## 7. Compatibilità con i database esistenti

* **Installazioni correnti (v≤15)**: nessun cambio di schema (versione resta 15): Room non esegue migrazioni in più; lo schema 15 è identico. Vengono solo esportati gli schemi e cambia la gestione dei casi anomali. L'unica differenza visibile a runtime è il nuovo preflight (apre il file in sola lettura per leggere `user_version`).
* **v1/v2** (build di agosto 2026): ora migrabili; prima venivano azzerati.
* **File più nuovo** (es. build vecchia installata sopra una nuova): rifiutato senza modifiche.
* **Backup/restore**: formato invariato. `restore()` produce uno staging DB; `MIN_SUPPORTED` ora è 1 (prima 3) perché 1–2 sono migrabili davvero. Il callback `RestoreSanitizeCallback` resta in `onOpen`, dopo le migrazioni. Un database ripristinato di versione < 15 passa dal preflight e dallo snapshot come un upgrade normale. L'interruzione a metà di una migrazione è coperta da Room (transazione per migrazione) e dallo snapshot.
* **Archivio, note, attività, automazioni, memoria**: nessuna tabella o colonna modificata; migrazioni solo additive.

## 8. Limiti degli schemi storici

* `exportSchema` era `false` fino a ora: **nessuno schema JSON storico esiste nel repository**, e non possono essere generati senza Android SDK (Room/KSP). Non sono stati scritti a mano né dedotti.
* Percorso previsto: `tools/db/export_historical_schemas.sh` (workflow manuale `Room historical schemas`) genera `N.json` dal codice del commit che ha rilasciato la versione N. Mappatura calcolata qui con `git` (ultimo commit con `version = N`):
  `v1 9396632 · v2 a52720f · v3 6054522 · v4 4eba556 · v5 b6bd503 · v6 0a0f426 · v7 9d59e3f · v8 e8cc2d4 · v9 e1982cf · v10 60009a5 · v11 c0c9eea · v12 e9b93ed · v13 7d527d5 · v14 15dbea3`.
* **Possibili fallimenti**: un commit storico potrebbe non compilare con la toolchain attuale o richiedere asset/AAR mancanti (es. `app/libs/sherpa-onnx*.aar` nei commit con Supertonic); in tal caso lo script scrive `MISSING.txt` e la versione resta **non verificabile via JSON** (resta coperta solo dal DDL scritto a mano e dai test raw-SQL per v1/v2). Va documentato lì, non sostituito con uno schema inventato.
* Lo schema **15** sarà generato dalla prima build CI con questi cambi; va committato (`app/schemas/com.simone.jarvismobile.background.JarvisDatabase/15.json`).
* Le versioni 1–2 sono comunque verificabili senza JSON perché contengono solo tabelle con entity invariate.

## 9. Protocollo di verifica Honor 200

Vedi la checklist separata: **`docs/ANDROID_OFFLINE_STEP02_HONOR200_CHECKLIST.md`** (12 prove, ciascuna con preparazione, passaggi, atteso, verifica, log da raccogliere, conseguenze). **Nessuna prova è stata eseguita.**

Regola ferrea: non cancellare né sovrascrivere i dati reali. Ogni prova distruttiva solo su installazione isolata (applicationId separato / secondo profilo utente / telefono di prova) o su una copia di backup.

## 10. Condizioni per considerare P0-1 risolto

P0-1 passa a **RISOLTO** quando tutte vere:

1. CI verde sul commit che contiene queste modifiche (compilazione `app`, `testDebugUnitTest` con `JarvisMigrationsTest`, lint, compile `androidTest`, step "Verify exported Room schema").
2. `app/schemas/.../15.json` committato e identico a quello generato.
3. Schemi storici 3–14 (almeno quelli recuperabili) committati; per ciascuno non recuperabile, motivo documentato.
4. `JarvisMigrationInstrumentedTest` **eseguito** (workflow emulatore o dispositivo di prova) e verde, incluse le versioni con JSON.
5. Nessun `fallbackToDestructiveMigration` su `jarvis.db` (già garantito dal test JVM).

Oggi: 5 ✔ (scritto, verificato staticamente), 1–4 pendenti. Rischio P0-1: **eliminato nel codice, non ancora dimostrato dall'esecuzione dei test**.

## 11. Condizioni ancora necessarie per chiudere P0-2

P0-2 resta **APERTO**: richiede evidenza fisica su Honor 200 del build prodotto dalla CI con queste modifiche (vedi checklist). Minimo per chiuderlo: prove 1, 2, 3, 4, 5, 11 e 12 con esito PASS documentato (data, build id, log); le prove 6–10 chiudono la baseline funzionale. Se una prova fallisce, P0-2 resta aperto con il log raccolto.

## 12. Criteri di completamento dello step

| Criterio (ordine di lavoro §7) | Stato |
|---|---|
| Fallback distruttivo generalizzato rimosso | ✔ nel codice |
| Nessuna cancellazione silenziosa per migrazione mancante | ✔ nel codice (+ test JVM, non eseguito qui) |
| Schemi correnti esportati | ✔ configurato; ✘ file 15.json non generato qui |
| Test reali di migrazione presenti | ✔ scritti; ✘ non eseguiti |
| Compila in CI | ✘ non verificato (non pushato) |
| Test disponibili superati | `:core` ✔ (vedi sotto); `app` non eseguibile qui |
| Funzionalità non rimosse | ✔ |
| Percorsi storici non verificabili documentati | ✔ (§8) |

**Stato finale: PARZIALE.** Il codice del PASSAGGIO 2 è completo ma non può essere dichiarato CODE COMPLETE finché la CI non lo compila e non verifica lo schema esportato.

---

### Risultato eseguito: test `:core`

`cd core && ./gradlew cleanTest test` → BUILD SUCCESSFUL, **1690 test, 0 falliti, 0 errori, 0 saltati** (163 classi). Nota di onestà: `:core` non è stato modificato in questo step e Gradle ha ripristinato il risultato **dalla build cache** per input identici (esecuzione reale già avvenuta nel PASSAGGIO 1), non ha rieseguito i test.
