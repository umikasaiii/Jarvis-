# JARVIS Android — PASSAGGIO 1/32 — Audit, inventario funzionale e baseline

Documento di sola analisi. Nessun codice di produzione, database, modello, chiave o dato personale è stato modificato. Nessun commit/push eseguito.
Il codice reale, il commit analizzato e i controlli eseguiti prevalgono su qualunque dichiarazione dei documenti non dimostrata.

---

## 1. Baseline

| Voce | Valore |
|------|--------|
| Repository | `https://github.com/umikasaiii/Jarvis-` (solo questa; `Jarvis-core`/`Jarvis-protocol` non toccate) |
| Branch analizzato | `claude/jarvis-mobile-automazioni-dashboard-b4xa7e` |
| Commit (HEAD remoto) | `550ca026b5d595dcf1f8c4d6c8a7910c875e7436` — "docs: reconcile LV-R1 (Master v1.28, CLAUDE.md, provenance)" |
| Ultimo commit di runtime | `e061297` (LV-R1: AEC3 + duplex + barge-in acustico) |
| Data verifica | 2026-11-07 |
| Come è stato analizzato | `git fetch` del branch + `git worktree add --detach /home/user/Jarvis-audit` (copia di sola lettura, working tree pulita, nessun reset/merge/checkout sul clone originale) |
| Clone originale (`/home/user/Jarvis-`) | Era su un branch **diverso e molto vecchio**: `ccr-a66d636a-ws75w4` @ `3ae7ffa` (395 commit indietro, nessun `docs/JARVIS_MASTER_ARCHITECTURE.md`, `CLAUDE.md` da 12 KB invece di 807 KB). Non è stato toccato; è il motivo per cui l'analisi usa il worktree. |
| Ambiente | Linux, JDK 21 (Gradle da wrapper), **nessun Android SDK/NDK** (`ANDROID_HOME` assente) |
| Stato working tree | pulita (nessuna modifica locale; solo questo file creato, non committato) |
| Dimensioni | `core/` 220 file Kotlin / ~22,8k righe + 160 file di test (~20k righe); `app/` 359 file / ~67,5k righe + 46 file di test (~6,7k) + 2 androidTest |
| Fonti lette | `CLAUDE.md` (807 KB: tabella fasi + note), `docs/JARVIS_MASTER_ARCHITECTURE.md` (v1.28, 313 KB, parti pertinenti), `docs/TEST_PLAN.md`, `docs/DEVICE_TEST_HONOR_200.md`, `docs/LOCAL_KNOWLEDGE.md`, doc di test device; poi **verifica diretta nel codice** |

**Convenzione degli stati** (gli stati richiesti dal task, usati così):
- **VERIFICATA SU DEVICE** — esiste evidenza documentata di prova reale su Honor 200 (la documentazione la riporta; io non ho dispositivo, quindi è evidenza di seconda mano, non riprodotta qui).
- **VERIFICATA IN CI** — il codice compila in CI **e** ha test automatici significativi sulla logica (tipicamente in `:core`). Non implica che l'integrazione `app/` funzioni a runtime.
- **IMPLEMENTATA, DA TESTARE** — codice presente e collegato al flusso, compilato in CI, ma senza test automatici sufficienti sul lato Android né prova device.
- **PARZIALE**, **NON COLLEGATA**, **NON IMPLEMENTATA**, **BLOCCATA** — come da definizione del task.

---

## 2. Inventario funzionale

Legenda offline: **SÌ** = funziona senza rete dopo l'eventuale preparazione; **PREP** = serve un download/import iniziale poi offline; **NO** = richiede rete/servizio; **n/a**.

### 2.1 AI e conversazione

| Funzione | File principali | Stato | Offline | Problemi / dipendenze | Prove |
|---|---|---|---|---|---|
| LLM locale LiteRT-LM (slot rapido + avanzato + classificatore) | `app/llm/LitertLmEngine.kt`, `LlmRouter.kt`, `ModelManager.kt`, `ui/models/*` | **VERIFICATA SU DEVICE** (Fase 3, build `045bb77`) + CI | PREP (l'utente importa un `.litertlm`; nessun modello nell'APK) | Nessun downloader in-app: import manuale. Conversazione KV-cache per slot, reset su cambio motore (`resetConversation`). | CLAUDE.md fase 3; CI #493 |
| Motore Classico (default) | `app/audio/SessionCoordinator.kt` (2.5k righe), `engine/ClassicJarvisEngine.kt`, `tools/CommandMatcher.kt` | IMPLEMENTATA, DA TESTARE (+ parti verificate: 1–3 su device) | SÌ | `SessionCoordinator` è un god-object (~30 dipendenze nel costruttore), non testabile in JVM. Default `JarvisEngineMode.CLASSICO`. | `CommandMatcherTest`, core |
| Motore Conversazionale (LLM-first) | `engine/ConversationalJarvisEngine.kt`, `JarvisBrain.kt`, `ToolRouter.kt`, `ContextAssembler.kt`, `ConversationManager.kt` | IMPLEMENTATA, DA TESTARE | SÌ (se modello caricato) | Opt-in (non default). Nessun test JVM su `ConversationalJarvisEngine`/`JarvisBrain`. Se il modello non è caricato cade su Classico. Nessun test device del motore completo (solo checklist `DEVICE_TEST_HONOR_200.md`, righe Pass/Fail vuote). | core: `GroundingGate`, `RelevantToolSelector`, `ToolLoopEvidence` ecc. |
| Strato semantico "authoritative" (SemanticFrame→validator→merger→router) | `core/semantic/*` (23 file), `app/engine/semantic/*` | PARZIALE | SÌ | **Vedi P1-1**: la sorgente primaria (EmbeddingGemma) non può diventare pronta; senza di essa ogni turno del motore Conversazionale va al ciclo LLM completo. | core: 1690 test verdi (modelli finti) |
| EmbeddingGemma 300M + tokenizer | `app/llm/EmbeddingGemmaEngine.kt`, `SemanticTokenizer.kt` (`NotReadyTokenizer`) | **BLOCCATA** | — | Tokenizer di produzione = `NotReadyTokenizer` (mai pronto, fail-closed). Nessun SentencePiece reale; nessun `.tflite` nell'app/repo; nessuna testa addestrata (`head_weights.json` assente); Pass 14B in pausa (richiede PC dell'utente). | `app/src/main/assets/semantic/prototypes.json` (176 prototipi: corpus, non modello) |
| Interprete generativo legacy (Gemma 1B) | `engine/semantic/LocalLlmSemanticInterpreter.kt` | NON COLLEGATA al percorso automatico | SÌ | Raggiungibile solo dal toggle debug `semanticUseLegacyGemmaInterpreter` (default `false`). | — |
| Classificatore LLM one-line (fast path Classico) | `tools/LlmIntentClassifier.kt` | IMPLEMENTATA, DA TESTARE | SÌ | Terzo slot opzionale. | `LlmIntentClassifierTest` (app) |
| Router intent deterministico (agenda delete/move/rename/complete/query) | `core/intent/*`, `tools/AgendaIntentRouter.kt` | VERIFICATA IN CI | SÌ | Limite lessicale dichiarato (non collega verbi diversi). | `IntentMetricsTest` (precision/recall su 57 frasi), core |
| Tool-calling (55 tool registrati) | `tools/*`, `core/tools/*`, `di/ToolsModule.kt` | VERIFICATA IN CI (policy/registry/outcome in `:core`) + IMPLEMENTATA DA TESTARE (esecutori Android) | SÌ (tranne rete: meteo) | Risultati strutturati (`StructuredToolResult`, 8 stati) migrati solo su alcuni tool (~125 call site restano sul percorso legacy). | `ToolRegistryTest`, `StructuredToolResultTest`, `GroundingGateTest` |
| Grounding fail-closed + guardia di presentazione | `core/engine/GroundingGate.kt`, `PresentationGuard.kt` | VERIFICATA IN CI | SÌ | Comportamento col modello reale non verificato. | core tests |
| Memoria conversazionale (4 livelli) | `engine/MemoryEngine.kt`, `engine/memory/*` (Room `conversational_memory`), `memory/ConversationMemoryStore.kt` | IMPLEMENTATA, DA TESTARE | SÌ | Facciata su più store (vedi §4). | `core` memory tests |
| Elaborazione in background (coda persistente) | `background/AssistantTaskQueue.kt`, `AssistantTaskWorker.kt` (WorkManager) | IMPLEMENTATA, DA TESTARE | SÌ | Watchdog 90 s; sanitizzazione dopo restore. **La UI `TasksScreen` della coda non è collegata** (vedi 2.5). | `AssistantTaskRestoreSanitizerTest` (compile CI) |
| Modalità Pro (LLM→tool locali, Note/Da vedere) | `promode/*`, `docs/PRO_MODE.md` | IMPLEMENTATA, DA TESTARE | SÌ | Percorso condizionale dentro Classico. | core |
| AiRouter / Core PC client / Event Bridge | `ai/*`, `corebridge/*` | IMPLEMENTATA ma **fuori scopo**: `coreEnabled=false`, `EVENT_BRIDGE_REMOTE_TRANSPORT_ENABLED=false` | NO (richiede PC) | Non necessaria per Android; `AiRouter.attemptRemoteOnly` è richiamato da `SessionCoordinator`/`JarvisBrain` ma con default spento. Cleartext HTTP abilitato per tutta l'app (vedi P2). | `AiRouterTest`, `RemoteAiEngineTest` |
| Personal Intelligence Snapshot | `snapshot/*`, `core/snapshot/*` | VERIFICATA IN CI (logica) / IMPLEMENTATA DA TESTARE (provider) | SÌ | Iniettato in `ContextAssembler`; fail-open. | 31 test core |
| Responsibility kernel (PA-1A/1B) | `core/responsibility/*`, `app/responsibility/*` (Room `agent_responsibilities`) | **NON COLLEGATA** | n/a | Contratti + store + reducer puri; zero integrazione runtime (PA-2 non iniziato). La migrazione 14→15 è però attiva. | 58 test core/app |

### 2.2 Voce

| Funzione | File principali | Stato | Offline | Problemi / dipendenze | Prove |
|---|---|---|---|---|---|
| STT offline (Android on-device recognizer) | `audio/AndroidOnDeviceSpeechEngine.kt` | **VERIFICATA SU DEVICE** (Fase 2, `8ee7275`) | PREP (pacchetto lingua IT offline di Android) | Dipende dal riconoscitore di sistema (MagicOS). Retry/backoff su cold-start. Nessun STT streaming/whisper. | CLAUDE.md fase 2 |
| TTS Android | `audio/AndroidOfflineTtsEngine.kt`, `HybridTtsEngine.kt` | IMPLEMENTATA, DA TESTARE (fase 4) | PREP (voce italiana offline installata) | È il **fallback effettivo**: l'engine predefinito è `supertonic` (stub). | CI |
| TTS neurale Kokoro / Piper (ONNX) | `tts/KokoroTtsEngine.kt`, `PiperTtsEngine.kt`, `NeuralTtsRepository.kt`, `core/tts/*` (G2P it) | IMPLEMENTATA, DA TESTARE | PREP (file importati dall'utente) | Nessuna evidenza device nei documenti letti. Piper tipico 22,05 kHz → niente barge-in acustico. | G2P: test core |
| TTS Supertonic 3 | `tts/SupertonicTtsEngine.kt` | **BLOCCATA / stub** | — | AAR sherpa-onnx e 7 file modello mai aggiunti; engine sostituito da stub che ritorna `Failed` (→ fallback Android). Default `ttsEngineId = supertonic` => ogni avvio passa dal fallback. | CLAUDE.md 2026-08-19 |
| Wake word (foreground) | `audio/WakeWordEngine.kt` (`RecognizerWakeWordEngine`), `WakeWordController.kt` | IMPLEMENTATA, DA TESTARE | SÌ | Cicla `SpeechRecognizer`, solo con home in primo piano; **non** è un keyword-spotter, non funziona a schermo spento. | `WakeWord` core test |
| Silero VAD (diagnostica) | `audio/silero/*`, `core/voice/silero/*` | PARZIALE (evidenza device parziale: 3 run) | SÌ (modello importato dall'utente, MIT) | Non collegato a conversazione/STT; soglia 0.5 non qualificata. | `docs/DEVICE_TEST_LIVE_VOICE_PHASE_0_8.md` (parziale) |
| Cattura PCM canonica + proprietà unica microfono | `core/voice/PcmCaptureEngine.kt`, `audio/AudioRecordPcmSource.kt` | VERIFICATA IN CI | SÌ | Guardie source-scan contro più `AudioRecord`. | `SingleCaptureOwnerRegressionTest` |
| AEC3 (WebRTC) + duplex + barge-in acustico | `native/aec3/*`, `voice/aec/*`, `audio/duplex/*`, `core/voice/{AecDuplexPipeline,LiveDuplexMonitor}.kt` | VERIFICATA IN CI (compila, build nativa riproducibile, 33 test core) — **DA TESTARE su device** | SÌ | Default OFF. Solo voci neurali a 16/24/32/48 kHz; Android TTS/Piper 22,05 kHz negati. Nessun ritardo esterno inventato. | CI #493; piano `docs/DEVICE_TEST_LIVE_VOICE_REALTIME_LV_R1.md` (nessuna riga eseguita) |
| Barge-in a tocco | `SessionCoordinator.interruptAndListen()` | IMPLEMENTATA, DA TESTARE | SÌ | — | — |
| Traduttore bidirezionale live | `translate/*` (ML Kit `translate` + `language-id`), `ui/livetranslate/*`, `core/translate/*` | IMPLEMENTATA, DA TESTARE | PREP (modelli di lingua ML Kit scaricati una volta) | Ciclo sequenziale ascolta→segmenta→traduci→parla sull'STT di sistema (non simultaneo, non "veloce e naturale" nel senso full-duplex). ML Kit richiede Google Play Services e download dei modelli. | 15 test core, nessun test device registrato |
| Diagnostica vocale per turno | `audio/VoiceTurnDiagnosticsRecorder.kt`, `core/voice/VoiceTurnDiagnostics.kt` | VERIFICATA IN CI | SÌ | — | test core |

### 2.3 Conoscenza e documenti

| Funzione | File principali | Stato | Offline | Problemi / dipendenze | Prove |
|---|---|---|---|---|---|
| Libreria di conoscenza (cartella SAF) | `knowledge/KnowledgeRepository.kt`, `core/knowledge/*` | PARZIALE | SÌ | **Solo testo semplice** (`.md/.markdown/.txt/.text`, ≤4000 file, ≤4 MB/file). Lessicale (BM25-like) con riferimenti di fonte; nessun PDF/EPUB/HTML/ZIM. | `KnowledgeIndexer/Search` test core |
| Archivio documenti importati (PDF/DOCX/txt/immagini + OCR) | `document/DocumentImportManager.kt`, `DocumentTextExtractors.kt` (pdfbox-android), `ImageOcr.kt` (ML Kit text-recognition), tabelle Room | IMPLEMENTATA, DA TESTARE | SÌ (OCR Latin bundled) | Pipeline reale, anche dalla chat (+) e da Archivio › Documenti. Cartelle/rinomina OK. Ricerca lessicale; semantica solo se è importato un embedder ONNX. | core `document` test |
| Ricerca semantica (RAG ibrido) su memoria/archivio | `memory/EmbeddingRepository.kt` + `OnnxEmbedder.kt` (WordPiece), `memory/MemoryIndex.kt`, `archive/ArchiveRepository.kt` | IMPLEMENTATA, DA TESTARE | PREP (embedder ONNX importato dall'utente) | Senza modello: solo lessicale. **È un embedder diverso** da EmbeddingGemma (due sistemi di embedding). | `RetrievalRanker` test |
| **Kiwix / ZIM** | solo commento `KnowledgeRepository.kt:161` + `docs/LOCAL_KNOWLEDGE.md` | **NON IMPLEMENTATA** | — | Nessuna apertura di Kiwix, nessuna lettura ZIM, nessuna ricerca, nessuna interrogazione AI. `grep -i kiwix|zim|libzim` = 0 nel codice. (Il branch separato `claude/offline-vault-kit-v1` apre Kiwix come app esterna e incolla estratti a mano — **non** presente qui.) | grep |
| Offline Hub | assente nel branch canonico | **NON IMPLEMENTATA** (esiste solo sul branch `claude/offline-vault-kit-v1`, divergente) | — | Vedi §8/§10. | `git merge-tree` |
| Strumenti `search_knowledge/search_documents/search_archive/read_document_context/search_images` | `tools/KnowledgeTools.kt`, `ArchiveTools.kt` | IMPLEMENTATA, DA TESTARE | SÌ | Tre livelli di ricerca distinti per scelta (conoscenza / archivio / documenti). | `CommandMatcherTest` |

### 2.4 Navigazione

| Funzione | File principali | Stato | Offline | Problemi / dipendenze | Prove |
|---|---|---|---|---|---|
| Navigatore interno (MapLibre + PMTiles + A*) | `navigation/*`, `core/navigation/*`, `ui/navigation/*` | IMPLEMENTATA, DA TESTARE (core VERIFICATA IN CI: matching, progresso, deviazione, generazioni stale) | PREP (regione `.pmtiles` + grafo/ricerca da pipeline `tools/navigation/osm_to_jarvis.py` o manifest con URL) | Le regioni non sono prodotte dall'app: servono file generati su PC/ospitati. Ricalcolo fuori percorso OK. Valhalla = stub. | core navigation tests; `RouteGenerationGate` ecc. Nessuna evidenza device nel doc letto. |
| Mappa online di base e routing/ricerca TomTom | `OnlineMapStyleFetcher.kt` (OpenFreeMap), `TomTomRoutingEngine.kt`, `TomTomSearchFetcher.kt`, `TomTomTrafficFetcher.kt` | IMPLEMENTATA, DA TESTARE | **NO** (rete + chiave TomTom dell'utente) | Fallback quando manca la regione offline; invia coordinate/testo a TomTom (documentato in PRIVACY). Forma payload mai ispezionata dal vivo. | — |
| Ricerca luoghi offline (FTS) | `PlaceSearchRepository.kt`, `RegionSearchIndex.kt` | IMPLEMENTATA, DA TESTARE | PREP | Dipende dagli asset di regione. | core `DestinationResolver` test |
| Modalità Guida V1 (overlay su **Google Maps**) | `driving/*`, `ui/driving/*`, `DrivingMapsLauncher.kt` | IMPLEMENTATA, DA TESTARE | Dipende da Google Maps | Lancia **Google Maps** (`geo:`/`google.navigation:`), nessuna informazione reale sul percorso di ritorno all'app. Richiede `SYSTEM_ALERT_WINDOW`. | core driving tests |
| Modalità Guida V2 (Activity interna) | `DrivingModeActivity`, componenti Compose | IMPLEMENTATA, DA TESTARE | PREP | Dietro flag sviluppatore. | `golden` layout test core |
| **Organic Maps** | — | **NON IMPLEMENTATA** | — | Nessun intent, package, `<queries>` o stringa (`grep organicmaps` = 0). Né apertura, né destinazione, né navigazione, né lettura del percorso. | grep |
| Geofence luoghi (senza Play Services) | `automation/rule/PlaceGeofenceSource.kt`, `PlaceProximityReceiver.kt` | IMPLEMENTATA, DA TESTARE | SÌ | Richiede `ACCESS_BACKGROUND_LOCATION`. | `TriggerMatching` core |

### 2.5 Organizzazione personale

| Funzione | File principali | Stato | Offline | Problemi | Prove |
|---|---|---|---|---|---|
| Memoria (note/ricordi stile app Note) | `ui/memory/MemoryScreen.kt` (1792 righe), `core/memory/*`, store locale `Memoria.md` | IMPLEMENTATA, DA TESTARE | SÌ | Editor a schermo intero, cartelle/sottocartelle ("/"), markup inline (grassetto, corsivo, colori, evidenziatore, dimensione, allineamento, checklist spuntabile in anteprima), temi/sfondi anche importati, **nessuna immagine inline** né allegati. Sync Obsidian rimosso. | `MemoryMarkupTest`, `MemoryRecordTest` |
| Archivio › Note/Liste/Da vedere/Documenti | `ui/archive/ArchiveScreen.kt` (1521), `archive/*` (Room `archive_items`, `archive_lists`), `core/archive/*` | IMPLEMENTATA, DA TESTARE | SÌ | **Secondo editor/secondo store di note** (vedi §4). Cartelle a un livello via "/", pin, temi, riuso del markup di Memoria. Documenti = pipeline `DocumentImportManager`. | `ArchiveItemTest` |
| Agenda / Attività (stile Google Tasks) | `ui/agenda/AgendaScreen.kt` (965), `AgendaViewModel`, `agenda/AgendaRepository.kt`, `core/agenda/*` | IMPLEMENTATA, DA TESTARE | SÌ | **Dati reali e persistenti** (`JARVIS/Agenda.md`, fallback privato + mirror vault), nessun elemento demo. Liste, ⭐, sotto-attività a un livello, note, completate con data, promemoria multipli. **Mancano: ricorrenze delle attività** (nessun campo nel modello `AgendaEntry`; le ricorrenze esistono solo nelle automazioni), date picker completo. | `AgendaTest`, `AgendaEvidenceTest`, `ItalianDateTimeParser` |
| Promemoria con allarme esatto | `reminders/*`, `alarms/ExactAlarms.kt` | **VERIFICATA SU DEVICE** (Fase 6e: notifiche puntuali ad app chiusa) | SÌ | `SCHEDULE_EXACT_ALARM`/`USE_EXACT_ALARM`. | CLAUDE.md 6e |
| Coda di elaborazione AI (`TasksScreen`/`TasksViewModel`) | `ui/tasks/*` | **NON COLLEGATA** | n/a | Schermata e ViewModel esistono ma nessuna navigazione le raggiunge (`Overlay` non ha `TASKS`; nessun altro riferimento). Distinta correttamente dalle attività personali, ma è codice morto. | grep |
| Liste (spesa, personalizzate) | `archive/ArchiveListRepository.kt` | IMPLEMENTATA, DA TESTARE | SÌ | — | core |
| Widget | `widget/*` (Glance) | IMPLEMENTATA, DA TESTARE | SÌ | — | `WidgetUpdaterPreBarrierRegressionTest` |

### 2.6 Proattività e automazioni

| Funzione | File principali | Stato | Offline | Problemi | Prove |
|---|---|---|---|---|---|
| Briefing mattutino | `proactive/*`, `core/proactive/*` (10 file) | **PARZIALE — fallimento device documentato** | SÌ (meteo/salute opzionali) | Honor 200: dopo reinstallazione ha funzionato 1 volta; dal 2° giorno solo il fallback orario (08:50) (Master §30.17). NEXT_ALARM e FIRST_UNLOCK non affidabili. Duplicati risolti in CI (occorrenza atomica, one-shot) ma **non riverificati su device**. | `ProactiveOccurrenceStoreTest` (JVM), core; device: FAILED/RETEST REQUIRED |
| Avviso pioggia/temporali serale | `core/weather/WeatherAlertPolicyV2.kt`, `proactive/ProactiveManager.evaluateWeatherAlert()` | IMPLEMENTATA, DA TESTARE | NO (Open-Meteo) | Soglie candidate non validate meteorologicamente; receipt locali. | 46+ test core |
| Automazioni "classiche" (6f, Markdown) | `automation/*`, `core/automation/*` | IMPLEMENTATA, DA TESTARE | SÌ | Ricorrenti, condizionali, comando differito; servizio FGS opt-in. | core |
| Motore regole generico (6j: trigger+condizioni+azioni) | `automation/rule/*`, Room `automation_rules/*` | PARZIALE | SÌ | Coesiste col 6f; fasi 9–10 (rule builder completo, subentro) non fatte. Dedup durabile a 3 livelli (verificata in JVM). | `ExecutionGateTest`, `DurableDedupTest` |
| Modalità JARVIS (HOME/WORK/…) | `mode/JarvisModeManager.kt` | IMPLEMENTATA, DA TESTARE | SÌ | — | core `JarvisModes` |
| Servizi in background | `AutomationEventService` (FGS `specialUse|microphone`), WorkManager, `BootReceiver` | IMPLEMENTATA, DA TESTARE | SÌ | Notifica persistente; su MagicOS i tre segnali del briefing restano inaffidabili. | — |

### 2.7 Altre funzioni

| Funzione | File principali | Stato | Offline | Problemi | Prove |
|---|---|---|---|---|---|
| Salute (Health Connect: sonno, FC a riposo) | `health/HealthConnectManager.kt`, `core/health/*` | **VERIFICATA SU DEVICE (parziale)** (permessi/dati reali riportati; numeri confrontati con Honor Health) | SÌ | Dipende da cosa Honor Health sincronizza; libreria connect-client alpha. | `HealthDailySeriesTest` (15), core |
| Meteo (Open-Meteo) | `weather/*`, `core/weather/*` | IMPLEMENTATA, DA TESTARE | **NO** (opt-in, eccezione sanzionata) | Cache con tag di posizione; soglie qualitative versionate. | 80+ test core |
| Dashboard + temi (Blu/Rosso/Rouge/Atena) + Stato Sistema | `ui/dashboard/*`, `ui/theme/*`, `ui/status/*` | IMPLEMENTATA, DA TESTARE | SÌ | Layout molto custom a pixel; SEGNALE (design system P0) usato solo in Diagnostica. | — |
| Backup locale cifrato + restore a staging | `backup/*` (16 file), `core/backup/*` | VERIFICATA IN CI (logica: manifest HMAC, staging, recovery) / DA TESTARE su device | SÌ (locale/SAF) | Restore: db/datastore applicati al cold start successivo. | core backup tests; `RestoreStagingTest` |
| Backup cloud Google Drive | `backup/drive/*` | IMPLEMENTATA, DA TESTARE | **NO** (opzionale) | Richiede Google Play Services (`play-services-auth`) + client OAuth Android; unica dipendenza GMS (ADR 0015). | — |
| Diagnostica | `ui/diagnostics/*`, `diagnostics/StartupDiagnostics.kt` | VERIFICATA IN CI | SÌ | Molte card debug. | — |
| Sicurezza/privacy | `core/security/LogRedactor`, `docs/SECURITY.md`, `PRIVACY.md`; `allowBackup=false` | VERIFICATA IN CI (redazione log) | SÌ | Vedi P2 (cleartext). | `LogRedactorTest` |
| Import/export | Documenti, backup, impostazioni modelli | IMPLEMENTATA, DA TESTARE | SÌ | — | — |
| Modalità Guida/UI Maps overlays | vedi 2.4 | — | — | — | — |

Totale censito: **~75 voci funzionali** (di cui 6 classificate con stato di sola evidenza device parziale/valida, ~10 BLOCCATE/NON IMPLEMENTATE/NON COLLEGATE), a loro volta appoggiate a **40 package** `app/` e **31 package** `:core`. Ulteriori elementi non documentati nei file principali trovati: 55 tool registrati (non tutti elencati nei doc), `ConversationalMemory` su Room, `ForecastDecisionReceipt` (DB separato `weather_decisions.db`), `TriggerEvidence`, `StartupDiagnostics`, `ProactiveOccurrence`, `NoteBackgroundStore`.

---

## 3. Mappa dell'architettura (flussi reali)

**Chat/voce → risposta.** Orb/Tile/wake word → `SessionCoordinator.startSession()` (unico ingresso) → `AndroidOnDeviceSpeechEngine.transcribe()` → `processTurn()` → `JarvisEngineRouter.route()`:
- **CLASSICO (default)**: `CommandMatcher` (regex deterministiche) → `AgendaIntentRouter` (delete/move/rename/complete/query) → classificatore LLM one-line (solo se il testo sembra un'azione, `ToolIntentGate`) → altrimenti `chatReply()` (LLM locale, memoria/archivio/documenti iniettati come contesto; in opt-in prova prima Core PC) → `speakOut()` → follow-up.
- **CONVERSAZIONALE (opt-in)**: pending → `FastPathRouter` (solo torcia/media) → `runStructuredPath` agenda → `HomeControlDetector` → `runSemanticPath` (interprete semantico: oggi sempre `Invalid`, vedi P1-1) → `runBrainLoop` (LLM + `RelevantToolSelector` + `ToolRouter` fino a 4 giri + `GroundingGate`).
- **Fonti**: memoria (`MemoryIndex`), archivio (`ArchiveRepository`), documenti (`DocumentImportManager`), libreria cartella (`KnowledgeRepository`) — ciascuna con un tool distinto, nessuna orchestrazione unica.
**TTS**: `HybridTtsEngine` sceglie neurale (Kokoro/Piper, `PcmPlayer`/`AudioTrack`) o Android TTS; il far-end PCM viene esposto solo per il neurale.
**Navigazione**: `NavigationRepository` (unico proprietario) → `NavigationEngine` (A* offline; fallback TomTom online) → `NavigationLocationProvider` (GPS) → `JarvisMapView` (MapLibre/PMTiles). Percorso parallelo: Modalità Guida V1 lancia **Google Maps** via Intent.
**Traduzione**: `LiveTranslatorManager` → STT di sistema → `MlKitLanguageDetector` → `MlKitTranslationEngine` → `TranslationAudioController` (TTS multilingue).
**Memoria/note**: `MemoryIndex` (file locale `Memoria.md` + eventuale vault) ↔ `ArchiveRepository` (Room) ↔ `ConversationMemoryStore`/`conversational_memory` (Room).
**Notifiche/proattività**: scheduler (`ProactiveScheduler`: NEXT_ALARM + CONFIGURED_TIME, `AutomationEventService`: FIRST_UNLOCK) → `ProactiveManager.run()` → claim atomico (`ProactiveOccurrenceStore`, Room) → `ProactiveDeliveryDispatcher` → `ProactiveNotifier`.
**Backup**: `BackupWorker` (WorkManager) → `BackupRepository` (snapshot incrementale cifrato AES-GCM, manifest HMAC) → destinazione locale / SAF / `CloudSyncManager` → Drive.

---

## 4. Analisi delle duplicazioni

| Area | Sistemi sovrapposti | Giudizio |
|---|---|---|
| Note | `MemoryRecord` (file locale `Memoria.md`, `MemoryScreen`) **vs** `ArchiveItem` (Room, `ArchiveScreen`) — due editor, due store, stesso markup (`MemoryMarkup` riusato) | **Da unificare** nel passo "Blocco Appunti" (una sola API/store, una sola UI; l'altra come vista/migrazione). Non creare un terzo. |
| Attività | `AgendaRepository` (Markdown, attività+appuntamenti) **vs** `AssistantTaskQueue` (Room, coda AI) | Distinti correttamente. `TasksScreen` morta: eliminare o collegare come "Elaborazioni". |
| Embedding | `OnnxEmbedder` (sentence-transformer WordPiece, memoria/archivio) **vs** `EmbeddingGemmaEngine` (semantica del routing) | Due runtime e due tokenizer; nessuno dei due ha modello nell'APK. Decidere un solo embedder per RAG e routing. |
| Routing/comprensione | `CommandMatcher` + `LlmIntentClassifier` (Classico) **vs** `SemanticInterpreter`+`RelevantToolSelector` (Conversazionale) **vs** `core/routing` (scaffold mai collegato) | Tre sistemi; solo i primi due sono vivi. |
| Conoscenza/documenti | `KnowledgeRepository` (cartella SAF, testo) **vs** `DocumentImportManager` (Room, PDF/OCR) **vs** archivio | Tre percorsi di ingestione e di ricerca; la nuova "grande biblioteca" (ZIM) dovrà agganciarsi a uno solo. |
| Navigazione | Navigatore interno **vs** TomTom online **vs** lancio Google Maps (Modalità Guida V1) **vs** (assente) Organic Maps | Quattro percorsi; Organic Maps richiede una scelta esplicita di ruolo. |
| Automazioni | Motore 6f (Markdown) **vs** motore 6j (Room) | Coesistono; `importLegacy()` non ha chiamanti (rischio di doppia esecuzione se collegato senza disattivare l'origine). |
| Memoria | `MemoryIndex`, `ConversationMemoryStore`, `conversational_memory` (Room), memoria breve | Quattro livelli per scelta, con facciata; verificare che non divergano. |
| Cattura microfono | `AndroidOnDeviceSpeechEngine`/wake (SpeechRecognizer) **vs** `AudioRecord` unico (`PcmCaptureEngine`) | Convivono per design e non devono sovrapporsi; guardie di regressione presenti. |
| Meteo | `fetchRain` / `fetchWeeklyOutlook` / `fetchDatedDailyForecast` (tre richieste distinte alla stessa fonte) | Voluto (isolamento della policy v2) ma costoso da mantenere. |

---

## 5. Problemi rilevati

### P0 — rischio per dati o stabilità

| ID | Problema | Evidenza | Impatto | File | Correzione proposta |
|---|---|---|---|---|---|
| P0-1 | `JarvisDatabase` è ancora costruito con `fallbackToDestructiveMigration()` e `exportSchema=false`. La catena 3→15 è completa (12 migrazioni, nessun buco), ma ogni futuro `version++` senza migrazione, o un upgrade da una versione <3, **azzera in silenzio** regole, luoghi, archivio, memoria episodica e responsabilità. Solo 2 migrazioni su 12 hanno un test (androidTest, **mai eseguito in CI**: solo compilato). Il crash E.1 sul candidato installato è stato proprio un'incoerenza schema/migrazione. | `di/DatabaseModule.kt:48-56`, `background/JarvisDatabase.kt:54-55`, `app/src/androidTest/...` (2 file) | Perdita dati utente non ricostruibili; crash all'apertura dopo upgrade | `di/DatabaseModule.kt`, `JarvisDatabase.kt` | Prima di aggiungere tabelle ai passaggi successivi: abilitare `exportSchema`, un test di catena completa 3→15 (MigrationTestHelper o equivalente), e sostituire il fallback distruttivo con un fallback limitato alle sole tabelle cache. Eseguire gli androidTest su emulatore/device. |
| P0-2 | Il fallimento **E.1 (crash all'avvio dopo upgrade)** è stato corretto e l'app si apre sul candidato `1236015`, ma il resto della matrice di accettazione Work Package E **non è stato eseguito**; sul dispositivo girano build ≥14 commit/versioni indietro rispetto a HEAD. | Master §30.16.1; `PROACTIVITY_FINAL_ACCEPTANCE.md` (righe PASS/FAIL vuote) | Non esiste una baseline device del build attuale | — | Installare `latest-debug` (`550ca02`) e fare la smoke-test di avvio + upgrade in-place prima del PASSAGGIO 2. |

### P1 — impediscono capacità principali

| ID | Problema | Evidenza | Impatto | Correzione proposta |
|---|---|---|---|---|
| P1-1 | **Il livello di comprensione semantica "authoritative" è inerte.** `SemanticInterpreterRouter` usa solo l'interprete EmbeddingGemma; il tokenizer è `NotReadyTokenizer`, non c'è `.tflite`/SentencePiece/testa addestrata → `SEMANTIC_MODEL_UNAVAILABLE` a ogni turno → il motore Conversazionale ricade sul ciclo LLM completo. Le 1690 verifiche core usano embedder finti. | `llm/SemanticTokenizer.kt:46`, `engine/semantic/SemanticInterpreterRouter.kt`, nessun `head_weights.json`/`.tflite` nel repo; Pass 14B in pausa | La "comprensione avanzata" dipende interamente dal modello LLM piccolo; nessun routing dominio-per-dominio; la qualità non è misurata sull'Honor | Decidere: (a) completare Pass 14B (tokenizer reale + modello + testa) oppure (b) ritirare il router embedding dal percorso automatico e promuovere un unico classificatore. Non dichiarare "comprensione semantica" finché non c'è evidenza reale. |
| P1-2 | **Briefing mattutino inaffidabile su device** (arriva solo dal fallback; NEXT_ALARM/FIRST_UNLOCK non osservati su più giorni). Le correzioni A–E sono in CI ma **mai verificate su device**. | Master §30.17, `DEVICE_TEST_MORNING_BRIEFING_DEDUP.md` | Funzione proattiva centrale non affidabile; rischio di duplicati/mancanti su MagicOS | Eseguire la checklist device su `550ca02`, leggendo la diagnostica persistente (`TriggerEvidence`), prima di nuovo lavoro sullo scheduler. |
| P1-3 | **Kiwix/ZIM e Organic Maps non esistono** nel branch canonico: nessuna integrazione, né apertura né lettura. | `grep -i kiwix\|zim\|organicmaps` = nessun risultato in `app/`/`core/` | La "grande biblioteca offline" e la navigazione GPS offline esterna sono interamente da costruire | Vedi §8; non riusare il branch `offline-vault-kit-v1` così com'è (§10). |
| P1-4 | **Nessun "Blocco Appunti" unico** e nessuna immagine/allegato nelle note: due store/editor paralleli (Memoria vs Archivio). | `ui/memory/MemoryScreen.kt`, `ui/archive/ArchiveScreen.kt`, `core/memory/MemoryRecord.kt`, `core/archive/ArchiveItem.kt` | Funzione richiesta (cartelle, checklist, immagini, ricerca) frammentata; rischio di divergenza dei dati | Unificare su un solo modello/store, aggiungere allegati immagine riusando `DocumentImportManager`, mantenere la migrazione delle due fonti. |
| P1-5 | **Le attività non hanno ricorrenze** (né sottoattività oltre un livello); il modello `AgendaEntry` non ha campo di ripetizione. | `core/agenda/AgendaEntry.kt` | Funzione "stile Google Tasks" incompleta | Estendere `AgendaEntry` (metadato backward-compatible in `Agenda.md`) e il motore dei promemoria esistente; non creare un secondo sistema. |
| P1-6 | **Libreria di conoscenza solo testo semplice** (md/txt); niente PDF/EPUB/ZIM nel percorso `KnowledgeRepository`; due altri percorsi (documenti, archivio) fanno cose parziali. | `knowledge/KnowledgeRepository.kt:161-170` | RAG sui file reali dell'utente limitato; nessuna fonte unica | Definire un'unica pipeline di ingestione (riusando `DocumentImportManager`) prima di aggiungere ZIM. |
| P1-7 | **TTS predefinito è uno stub**: `ttsEngineId = supertonic` → `Failed` → fallback Android TTS ad ogni sintesi; Kokoro/Piper non hanno evidenza device. | `tts/SupertonicTtsEngine.kt`, `data/SettingsRepository.kt:722` | Voce "naturale offline" non disponibile out-of-the-box | Cambiare il default a un motore realmente disponibile, o completare l'asset; verificare Kokoro/Piper su Honor. |
| P1-8 | **Test lato Android quasi assenti**: 46 file JVM in `app/src/test`, 2 androidTest compile-only, nessun Robolectric/emulatore; motori principali (`SessionCoordinator`, `ConversationalJarvisEngine`) non coperti. | `app/src/test`, `ci.yml` | Regressioni su flusso reale individuabili solo su device | Aggiungere un job emulatore (o device farm) per smoke test e migrazioni (collegato a P0-1). |

### P2 — limiti e miglioramenti non bloccanti

- **Cleartext HTTP abilitato globalmente** (`network_security_config.xml` base-config) per consentire Core PC su LAN; la funzione Core non serve ad Android → restringere o disabilitare di default.
- `SessionCoordinator.kt` (2,5k righe) e `ConversationalJarvisEngine.kt` sono troppo grandi: refactoring graduale con test.
- Codice morto/predisposto: `TasksScreen`, `core/routing/*` (scaffold mai collegato), `ValhallaNavigationEngine`, `JarvisEngineMode.IBRIDA`, `ResponsibilityKernel`, Event Bridge remoto.
- `jarvisContextBudgetChars` senza controllo in Impostazioni; Impostazioni cresce senza raggruppamento.
- Il file `CLAUDE.md` pesa 807 KB (cronologia di round); `docs/JARVIS_MASTER_ARCHITECTURE.md` 313 KB: conviene un sommario di stato canonico a parte.
- Wake word non è un keyword-spotter (cicla SpeechRecognizer, solo foreground).
- Traduttore sequenziale (non simultaneo); ML Kit richiede Google Play Services.
- Dipendenza `play-services-auth` (Drive) rompe la purezza "no-GMS" solo per il backup cloud opzionale.
- Documentazione Honor in ritardo: gran parte delle fasi 4–8b è "pending device check" da mesi.

---

## 6. Stato dell'autonomia offline

**Funziona senza rete dopo la preparazione (secondo codice ed evidenze):**
- STT (pacchetto lingua IT di Android), LLM LiteRT-LM (modello `.litertlm` importato), TTS Android, strumenti locali (timer, torcia, agenda, promemoria, ricerche locali), memoria/archivio/note/attività, documenti e OCR, automazioni, backup locale/SAF, navigazione interna con regione offline, traduzione dopo il download dei modelli ML Kit, Health Connect.

**Richiede preparazione iniziale (online o su PC):** modello `.litertlm` (import manuale), pacchetti di lingua STT/TTS di Android, modelli di traduzione ML Kit, voci Kokoro/Piper (file ONNX importati), embedder ONNX (opzionale), regioni di mappa (`.pmtiles`+grafo+indice, prodotti dalla pipeline `tools/navigation` o ospitati), modello Silero (importato).

**Dipende ancora da servizi esterni:** meteo (Open-Meteo), TomTom (routing/ricerca/traffico, chiave dell'utente), stile mappa OpenFreeMap quando non c'è la regione offline, backup Google Drive (Play Services), Core PC (opzionale, off). Nessuna di queste è necessaria per le funzioni essenziali, ma meteo/TomTom/Drive sono parte di funzioni richieste (briefing, navigazione online di riserva, backup cloud).

**Non verificato:** funzionamento reale in modalità aereo dell'intera catena sull'Honor con il build attuale; Kokoro/Piper/Translator/Archivio/Memoria su device; qualunque componente "pending device check" della tabella fasi.

---

## 7. Verifiche eseguite

| Verifica | Comando / fonte | Risultato |
|---|---|---|
| Baseline git | `git fetch`, `git worktree add --detach`, `git rev-parse HEAD`, `git status` | HEAD `550ca02`, working tree pulita |
| Test `:core` | `cd core && ./gradlew test` (JDK 21, Gradle da wrapper; il primo tentativo è fallito per 429 del repository Maven, il secondo è riuscito) | **BUILD SUCCESSFUL**: **1690 test, 0 falliti, 0 saltati**, 163 classi di test |
| Compilazione app Android | **Non eseguita localmente**: nessun Android SDK/NDK nell'ambiente | Evidenza di seconda mano: GitHub Actions CI #493 sul **commit esatto `550ca02`**: `Core unit tests`, `Assemble debug APK`, `Android unit tests`, `Compile instrumented tests (compile-only)`, `Android lint (debug)`, `AEC3 APK packaging report`, `Publish APK` → tutti **success**; job `PASS 14B Windows PowerShell 5.1` success; workflow `AEC3 native` success |
| Test unitari Android / Lint | CI (sopra) | verdi in CI; non rieseguiti qui |
| Test strumentati (androidTest) | — | **Non eseguibili** (nessun emulatore/device); solo compilati in CI |
| Evidenza device | Master/CLAUDE.md/DEVICE_TEST_* | Nessuna evidenza **nuova** raccolta in questo audit; nessuna prova su Honor eseguita da me |
| Verifica funzioni specifiche | `grep` mirati su codice (Kiwix, Organic Maps, `TasksScreen`, tokenizer, migrazioni, default engine, ecc.) | risultati riportati nelle sezioni 2, 4, 5 |
| Cosa non ho potuto fare | Eseguire l'app, `lint` locale, `testDebugUnitTest` locale, qualunque prova su Honor 200, verificare la CI di `claude/offline-vault-kit-v1` | dichiarato |

---

## 8. Impatto sui 31 passaggi successivi

**Riutilizzabile (completare/integrare, non riscrivere):**
- Note/Archivio/Memoria: `MemoryMarkup`, `ArchiveRepository`, `NoteBackgroundStore`, temi, cartelle; `DocumentImportManager` per allegati e OCR.
- Attività: `AgendaRepository`, `AgendaScreen`, `ExactAlarms`, motore promemoria (verificato su device).
- RAG: `KnowledgeIndexer/Search`, `RetrievalRanker/HybridRanker`, `EmbeddingRepository` (ONNX), `DocumentRetrieval`.
- Voce: pipeline STT/TTS, `PcmCaptureEngine`, Silero, AEC3 (solo da qualificare su device).
- Navigazione: `NavigationRepository`, `RouteGenerationGate`, MapLibre/PMTiles, ricerca FTS.
- Backup: `BackupRepository` (già con staging/HMAC); proattività: claim atomico e dispatcher.

**Richiedono vera implementazione:**
- Lettore ZIM/Kiwix (apertura app, lettura articoli, indice/ricerca, integrazione AI) — oggi assente.
- Integrazione Organic Maps (intent di navigazione, `<queries>` per il package, gestione offline delle mappe di terze parti) — oggi assente.
- Blocco Appunti unificato con allegati/immagini inline.
- Ricorrenze delle attività.
- Un embedder/tokenizer reale per la semantica (o ritiro del percorso embedding).
- Test device automatizzati (emulatore) e catena di migrazione.
- Stabilizzazione briefing su MagicOS.

**Dipendenze fra passaggi:** correggere P0-1 prima di qualunque nuova tabella Room (note unificate, indice ZIM, ricorrenze); risolvere la scelta embedder (P1-1) prima del RAG su biblioteca grande.

---

## 9. Checklist Honor 200 (da eseguire, **nessuna già superata**)

1. Installare `latest-debug` su `550ca02` in-place; verificare avvio senza crash e dati preservati; leggere la card "Avvio precedente".
2. Verificare `BuildConfig.BUILD_ID` in Diagnostica = `550ca02`.
3. Modalità aereo: importare/caricare un `.litertlm`, fare 5 turni (Classico) di conversazione con memoria, verificare nessuna richiesta di rete.
4. STT offline italiano, barge-in a tocco, follow-up.
5. TTS: Android vs Kokoro vs Piper (importando le voci), latenza, fallback.
6. Note/Memoria: creare cartella/sottocartella, nota con checklist, riaprire, cercare; stessa cosa in Archivio.
7. Attività: creare/completare/sposta/sotto-attività, promemoria esatto con app chiusa, riavvio del telefono.
8. Documenti: importare PDF, DOCX, foto con testo (OCR); chiedere in chat una frase presente solo nel documento (con modalità aereo).
9. Navigazione: installare una regione, calcolare un percorso offline, deviazione volontaria (ricalcolo), arresto; verificare che con rete assente non ci siano chiamate TomTom.
10. Traduttore: scaricare le lingue, test it↔en in aereo, valutare latenza e qualità.
11. Briefing mattutino: 3 mattine consecutive con diagnostica persistente attiva (FIRST_UNLOCK, NEXT_ALARM, orario); conteggio notifiche.
12. Salute: permessi Health Connect, confronto con Honor Health.
13. Backup locale + restore su installazione pulita; verificare che al cold start successivo i dati tornino e nessuna automazione ripetuta.
14. Live Voice: matrice `DEVICE_TEST_LIVE_VOICE_REALTIME_LV_R1.md` (barge-in acustico, solo voci neurali).
15. Consumi: batteria/RAM per 24 h con servizi in background attivi.

---

## 10. Decisione finale

**Già abbastanza maturo (con evidenza):** STT offline e LLM locale (verificati su device), promemoria con allarme esatto, logica di dominio in `:core` (1690 test), motore di routing deterministico, backup (logica), pipeline documenti/OCR (codice completo), CI verde sul commit esatto con build nativa AEC3 riproducibile.

**Da completare:** unificazione note, ricorrenze attività, pipeline di ingestione unica per RAG, ZIM/Kiwix, Organic Maps, semantica reale (o ritiro), voci neurali, test automatici Android/emulatore, verifica device di quasi tutta la tabella fasi.

**Da correggere subito (prima del PASSAGGIO 2):** P0-1 (fallback distruttivo + test catena migrazioni), P0-2 (baseline device del build attuale), la scelta sul percorso semantico (P1-1) per non costruire RAG su un router non funzionante, il briefing mattutino (P1-2) con evidenza device.

**Rischi nell'integrare Offline Hub** (`claude/offline-vault-kit-v1`, 2 commit basati su `3ae7ffa`, **395 commit indietro** rispetto a HEAD):
1. `git merge-tree` mostra **conflitti** in `ci.yml`, `AndroidManifest.xml` e `ui/JarvisApp.kt` (il canonico ha rimosso la barra di navigazione che l'Hub estende con un tab).
2. Duplica funzioni già presenti: `KnowledgeRepository.importDocument/saveExcerpt` (cartella `offline_reference`) vs `DocumentImportManager`/Archivio › Documenti (PDF/OCR, Room, backup); HTML/CSV normalizzati con `Html.fromHtml` diversamente dai pipeline esistenti.
3. L'integrazione Organic Maps/Kiwix dell'Hub è solo **lancio dell'app esterna** + incolla manuale di estratti: **non** è lettura/ricerca ZIM né interrogazione AI — non va conteggiata come tale.
4. Un secondo archivio "offline_reference" fuori dal backup e dalla tabella documenti introdurrebbe un altro store.
5. Il suo CI modifica lo step SDK (`sdkmanager`) mentre il canonico ha una pipeline molto più estesa (native AEC3, classifier APK, PowerShell 5.1).
Raccomandazione: **non fare merge**; riportare come patch selettive solo i `<queries>` per i package Organic Maps/Kiwix, e riusare idee (non codice) sull'estrazione HTML dentro `DocumentTextExtractors`.

**La repository è pronta per il PASSAGGIO 2?** **Sì, con condizione.** Il codice compila ed è coperto in CI sul commit esatto, il perimetro è chiaro e non servono riscritture. Ma prima di aggiungere tabelle o dipendenze del passaggio successivo conviene chiudere P0-1 (migrazioni) e fare la baseline device (P0-2); i rischi P1 sono funzionalità mancanti, non blocchi alla pianificazione. Non ho iniziato il PASSAGGIO 2.
