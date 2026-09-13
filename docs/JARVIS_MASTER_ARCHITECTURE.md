# JARVIS MASTER ARCHITECTURE

**Documento master di controllo — architettura, stato reale, decisioni, roadmap e vincoli**

- **Project:** JARVIS
- **Document role:** project map / architectural control plane / living source of project intent
- **Version:** 1.3
- **Generated:** 2026-09-13
- **Primary language:** Italiano
- **Status:** ACTIVE — living document
- **Repository target:** `umikasaiii/Jarvis-`
- **Recommended repository path:** `docs/JARVIS_MASTER_ARCHITECTURE.md`

> Questo file non sostituisce le fonti tecniche autoritative di dominio. Serve a mantenere sotto controllo l’intero progetto JARVIS, le decisioni già prese, le lezioni degli audit, le nozioni acquisite con Astra, gli invarianti architetturali, lo stato reale delle implementazioni e le dipendenze fra roadmap, Android, Core e protocollo.

---

# 0. COME LEGGERE QUESTO DOCUMENTO

## 0.1 Regola fondamentale

JARVIS non deve essere valutato con una sola etichetta “implementato/non implementato”. Per ogni capacità usare, dove rilevante, questa scala:

1. **DESIGNED** — architettura definita.
2. **CODE PRESENT** — codice presente nel repository.
3. **CONNECTED** — collegato al flusso reale.
4. **RUNTIME USED** — effettivamente attraversato in esecuzione.
5. **AUTOMATED TESTED** — coperto da test automatici.
6. **CI VERIFIED** — CI verde sullo SHA esatto.
7. **DEVICE VERIFIED** — verificato sul dispositivo reale.
8. **PRODUCTION READY** — tutti i gate pertinenti chiusi.

**Mai usare “completo” se un gate importante è ancora aperto.**

## 0.2 Legenda degli stati

| Stato | Significato |
|---|---|
| `VERIFIED` | evidenza reale verificata |
| `IMPLEMENTED` | implementato ma non implica device acceptance |
| `DEVICE-VERIFIED` | verificato sul dispositivo reale |
| `PLANNED` | approvato per roadmap |
| `CANDIDATE` | candidato tecnico da qualificare |
| `DEFERRED` | deliberatamente rimandato |
| `REJECTED` | deliberatamente escluso |
| `LEGACY` | storico, non parte dell’architettura corrente |
| `BLOCKED` | impossibile procedere finché un gate non è soddisfatto |
| `FAILED-DEVICE` | test automatici possono essere verdi, ma il comportamento reale ha fallito |

## 0.3 Gerarchia delle evidenze

Quando fonti o report sono in conflitto, prevale:

```text
DEVICE EVIDENCE / REAL RUNTIME
        >
LATEST VERIFIED CODE / COMMIT
        >
CI / AUTOMATED TESTS
        >
ARCHITECTURAL DOCUMENTATION
        >
OLDER REPORTS / ASSUMPTIONS
```

Esempio reale: il Morning Briefing aveva test verdi e inizialmente `MorningRefreshWorker` era stato considerato esonerato; il test reale Honor 200 ha prodotto tre briefing, e il successivo audit ha dimostrato che proprio il refresh path poteva generare un nuovo alert. **La nuova evidenza sostituisce la vecchia interpretazione.**

---

# 1. VISIONE DI JARVIS

JARVIS deve diventare un assistente personale Android-first, offline-first, proattivo, contestuale e modulare, capace di capire richieste naturali, usare dati reali, interagire con app/sistema/sensori/dispositivi, mantenere memoria persistente, funzionare anche senza Internet e anche con Core spento, e scalare verso modelli più forti senza rifare l’architettura.

## 1.1 Principio Android-first

Il telefono non è un terminale stupido del Core. **Android è JARVIS.** Il Core è un potenziamento.

```text
CORE OFF
→ JARVIS continua a funzionare per la maggior parte delle funzioni operative.

CORE ON
→ JARVIS usa più ragionamento, modelli più forti, archivi più grandi,
  elaborazioni pesanti e future azioni PC.
```

Target di prodotto indicativo:

- circa **70–80% delle capacità operative disponibili direttamente su Android**;
- circa **10–20% disponibili ma degradate senza Core**;
- circa **10–15% realmente Core-only**.

Queste percentuali sono target di prodotto, non metriche di codice.

---

# 2. REPOSITORY E RIFERIMENTI

| Componente | Repository |
|---|---|
| Android | `umikasaiii/Jarvis-` |
| Core | `umikasaiii/Jarvis-core` |
| Protocol | `jarvis-protocol` |

Branch Android attivo: `claude/jarvis-mobile-automazioni-dashboard-b4xa7e`

Branch Core storico: `claude/jarvis-core-7uhajh`

## 2.1 Stato remoto Android verificato al momento di generazione

Remote branch HEAD verificato:

`6a66d3247cff01a9dde36fb899196b22c16ff453`

Messaggio:

`Fix CI: missing assertFalse import in ProactiveOccurrenceStoreTest`

Parent:

`df686d525feab671fb93ed865649316bdce46073`

Il parent contiene la micro-patch **14.2.2** per il bug reale del Morning Briefing multi-delivery.

**Aggiornamento v1.2**: **CI run #433** sullo SHA esatto `6a66d3247cff01a9dde36fb899196b22c16ff453` è **COMPLETED / SUCCESS** (era `in progress` al momento della generazione v1.1). Stato verificato per questo commit:

```text
CODE PRESENT          ✅
CONNECTED             ✅
AUTOMATED TESTED      ✅
CI VERIFIED           ✅ Run #433
DEVICE VERIFIED       ❌
PRODUCTION READY      ❌
```

Honor 200: **RETEST REQUIRED** — CI verde non implica device acceptance (invariante §0.1/§62).

## 2.2 Stop-hook Git false positive noto

Il repository locale può mostrare un falso “unpushed” perché il `origin` fetch refspec locale non segue correttamente il branch attivo. La branch API GitHub remota è autoritativa per sapere se il commit è realmente pushato.

Non fare reset/rebase/cambio refs/ripush inutili solo per silenziare lo stop-hook.

---

# 3. DOCUMENTI ARCHITETTURALI DI RIFERIMENTO

- `JARVIS_ARCHITECT_MANUAL.md`
- `JARVIS_DEEP_AUDIT.md`
- `JARVIS_TARGET_ARCHITECTURE.md`
- `JARVIS DESIGN SYSTEM — SEGNALE`
- `JARVIS_MASTER_ARCHITECTURE.md` — questo documento

Ruoli:

```text
MASTER ARCHITECTURE
= mappa globale del progetto e stato reale

ARCHITECT MANUAL
= invarianti, regole, ownership, reliability, security

TARGET ARCHITECTURE
= architettura target

DEEP AUDIT
= evidenza, gap, debito tecnico

SEGNALE
= presentation system / visual language
```

---

# 4. INVARIANTI ARCHITETTURALI NON NEGOZIABILI

1. **Explicit current-turn meaning > previous context > defaults.**
2. **Model output != authorization.**
3. Ogni dato autorevole ha **un solo canonical writer / source of truth**.
4. Cache, embedding, graph e indici sono **derivati**, non autoritativi.
5. Permesso di lettura != permesso di esportazione.
6. Core OFF non deve distruggere le capacità essenziali Android.
7. Cambi protocollo: **protocol first**.
8. Nessuna pipeline duplicata senza owner, motivo e sunset.
9. `empty != failure != stale != permission missing`.
10. Build/model/artifact identity richiede evidenza.
11. Test verdi != device acceptance.
12. `present != connected != reachable != runtime-used != complete`.
13. Recommendation != fact.
14. Side-effect `UNKNOWN` => **no blind retry**.
15. Memory candidate != accepted fact.
16. Vision target != authorized action.
17. Nuovo modello != nuova architettura.
18. **Semantic Impact Check obbligatorio.**
19. Nessun hidden keyword router.
20. Un frontend non deve diventare un secondo cervello JARVIS.

---

# 5. ARCHITETTURA GLOBALE

```text
                         ┌──────────────────────────────┐
                         │          USER / WORLD         │
                         └──────────────┬───────────────┘
                                        │
               ┌────────────────────────┼────────────────────────┐
               │                        │                        │
             TEXT                     VOICE                    VISION
               │                        │                        │
               └────────────────────────┼────────────────────────┘
                                        ↓
                              INPUT / FRONTEND LAYER
                                        ↓
                              SEMANTIC INTERPRETER
                                        ↓
                                SEMANTIC FRAME
                                        ↓
                              PLANNER / ROUTER
                                        ↓
                     POLICY / CAPABILITY / AUTHORIZATION
                                        ↓
                                TOOL REGISTRY
                                        ↓
                ┌───────────────────────┼───────────────────────┐
                │                       │                       │
              ANDROID                CORE                    EXTERNAL
           capabilities          capabilities               services
                │                       │                       │
                └───────────────────────┼───────────────────────┘
                                        ↓
                                   REAL DATA
                                        ↓
                                 FACT / RESULT
                                        ↓
                                RESPONSE LAYER
                                        ↓
                  TEXT / VOICE / UI / NOTIFICATION / ACTION
```

---

# 6. ANDROID — RESPONSABILITÀ

Android possiede o deve possedere direttamente:

- UI e schermate;
- chat;
- AiCore presentation;
- sensori;
- GPS;
- geofencing;
- mic/camera;
- notifiche;
- overlay/modalità icona;
- driving mode;
- comandi sistema telefono;
- automazioni locali;
- agenda app-managed;
- integrazione con calendari esterni;
- Health Connect;
- weather access;
- storage locale;
- vault essenziale;
- FAST AI;
- semantic interpreter runtime leggero;
- wake word;
- fallback STT/TTS;
- proactivity;
- occurrence reliability;
- capability authorization lato device;
- diagnostica;
- offline mode.

Android deve rimanere utile con Core spento.

---

# 7. CORE WINDOWS — RESPONSABILITÀ

Core possiede o potrà possedere:

- BRAIN LLM;
- reasoning più profondo;
- planner agentico avanzato;
- grandi modelli;
- RAG esteso;
- memoria semantica estesa;
- grandi archivi;
- eventuali embedding/reranker pesanti;
- elaborazione multi-documento;
- future azioni Windows;
- voice engines più pesanti;
- background intelligence;
- future multimodal models più grandi.

Core non deve possedere in modo duplicato le fonti di verità Android.

---

# 8. PROTOCOLLO ANDROID ↔ CORE

Protocol baseline:

- `GET /v1/health`
- `GET /v1/capabilities`
- `GET /v1/models`
- `POST /v1/chat`
- `POST /v1/ai/request`
- `POST /v1/ai/stream`

Protocol version: `"1"`

Streaming SSE:

- `start`
- `token`
- `done`
- `error`

Cancellation: chiusura HTTP e propagazione.

Un campo è “supportato” solo se è provato end-to-end:

```text
Android serializer
→ protocol schema
→ Core deserializer
→ handler
→ actual consumer
```

`jarvis-protocol` è la source of truth del wire contract.

`/v2` obbligatorio è stato rifiutato: preferire v1 additive/negotiated salvo breaking change reale.

**Event Bridge: DEFERRED** finché non esiste un consumer reale.

---

# 9. NETWORK / PAIRING / SECURITY CORE

Stato attuale noto:

Core raggiunto in LAN a `192.168.1.10:8000`.

Target:

- pairing esplicito;
- TLS;
- identity del Core;
- token scoped;
- capabilities dichiarate;
- readiness reale di backend/model.

Il sistema deve distinguere almeno:

- rete disponibile;
- Core raggiungibile;
- autenticazione valida;
- backend pronto;
- modello pronto;
- richiesta accettata;
- request ID;
- deadline;
- queue state;
- modello effettivamente usato;
- terminal result.

---

# 10. SEMANTIC INTELLIGENCE

## 10.1 Flusso autoritativo

```text
USER TEXT
→ SEMANTIC INTERPRETER
→ SEMANTIC FRAME
→ PLANNER / ROUTER
→ DETERMINISTIC CAPABILITY / TOOL
→ REAL DATA
→ NATURAL RESPONSE
```

Il semantic classifier **non è** il modello che risponde all’utente.

Separazione permanente:

```text
SEMANTIC INTERPRETER
= capisce cosa significa la richiesta

PLANNER / BRAIN
= decide come rispondere / quali passi fare
```

## 10.2 Precedence rule

```text
EXPLICIT CURRENT TURN
        >
PREVIOUS CONTEXT
        >
DEFAULT
```

Il contesto può riempire slot mancanti. Non deve sovrascrivere il significato espresso chiaramente nel turno corrente.

## 10.3 SemanticFrame

Campi definiti:

- `intent`
- `domain`
- `operation`
- `temporal`
- `metric`
- `aggregation`
- `entities`
- `reference`
- `grounding`
- `confidence`
- `explicitSlots`
- `schemaVersion`

## 10.4 Open language / closed capability space

Il linguaggio dell’utente è aperto. Lo spazio delle capability è controllato. Un nuovo argomento di conoscenza non significa automaticamente “nuovo intent”. Una richiesta OOD può ancora essere gestita da open knowledge senza espandere artificialmente la tassonomia.

---

# 11. SEMANTIC IMPACT CHECK — OBBLIGATORIO

Ogni futura implementazione deve rispondere esplicitamente:

1. La modifica è rappresentabile con `intent/domain/op/slots` correnti? Se sì: **nessun classifier change**.
2. È un nuovo dominio/capability? Schema + dataset + hard negatives + retraining.
3. È una nuova operation/slot? Aggiungere esempi mirati.
4. È confondibile con qualcosa di esistente? Aggiungere hard negatives.
5. Dopo retraining: regressione old-domain + blind + OOD.
6. Mai usare keyword/regex per comprendere il significato generale.

Regex/parser consentiti solo per date, unità, ID e grammar strettissime di hard commands.

---

# 12. EMBEDDINGGEMMA / CLASSIFIER — STATO

Storia principale:

- `48a573a` — SemanticInterpreter / SemanticFrame / parser / merge.
- `e94a025` — Answer / HandoffToLlm / LegacyFallback.
- `50ab8c...` — failure classes device.
- `4efad73` — semantic router authoritative.
- `0cf7e0a` — EmbeddingGemma classifier.
- `dd13d9d` — trained-head infrastructure.

Problemi trovati prima del Pass 13:

- tokenizer production non valido;
- hashing whitespace mascherato da tokenizer;
- nessun artifact reale di trained head;
- eval/report poteva valutare fallback prototipo e chiamarlo learned head;
- validator bypass.

Ordine corretto stabilito:

```text
tokenizer/preprocessing parity
→ valid encoder path
→ fix eval/report
→ real embeddings/training
→ head
→ calibration/OOD
→ slots/reference/multidomain
→ blind
→ Honor device
```

**Mai addestrare prima di tokenizer parity ed evaluation integrity.**

---

# 13. PASS 13 — SEMANTIC FOUNDATION I

Final SHA: `8606250cccc9a555bba8946d874d44f36bf9e1ba`

Risultati:

- `SemanticEncoderContract`;
- `ArtifactQualification`;
- whitespace tokenizer rimosso da production;
- `NotReadyTokenizer` fail-closed;
- TFLite tensor contract validation;
- SHA-256 reale artifact identity;
- L2 normalization;
- zero/NaN fail-closed;
- LearnedHead compatibility wiring;
- diagnostics bounded;
- evaluation report integrity fix;
- cache key con contract identity.

Stato: **AUTOMATED/CI CLOSED**.

Ma real tokenizer qualification, real model artifact e real training sono ancora pending.

---

# 14. PASS 14 — REAL ARTIFACT / TRAINING GATE

Tooling commit: `91a9bc8730c11c522736275e98b80a2d12e4461a`

Stato reale: **TOOLING READY — REAL TRAINING BLOCKED BY ARTIFACT GATE**.

Claude environment non riusciva a raggiungere le fonti ufficiali EmbeddingGemma. Correttamente:

- nessun artifact fake;
- nessun tokenizer sostitutivo;
- nessun training sintetico spacciato per reale;
- nessun gate bypassato.

Shipped:

- `REAL_TRAINED`;
- `CalibrationStatus.PENDING/CALIBRATED`;
- dataset provenance;
- training seed;
- timestamp;
- dataset revision SHA-256;
- cache qualification;
- production gate REAL-only;
- export real fail-closed;
- selftest fix.

---

# 15. PASS 14B — REAL EXECUTION HANDOFF

**RUNNER READY — USER-PC REAL EXECUTION PENDING.** (era `PLANNED`)

Tooling commit: vedi §67. Obiettivo raggiunto in questo passaggio: preparare
l'intera catena per eseguire sul PC dell'utente acquisition artifact
ufficiale, manifest, SHA-256, tokenizer qualification, encoder
qualification, real TRAIN/VALIDATION/TEST embeddings, frozen-encoder
learned-head training, validation, TEST una volta, export `REAL_TRAINED`,
`CalibrationStatus.PENDING`, BLIND untouched — **non ancora eseguito
davvero**, per lo stesso motivo di Pass 14 (nessun accesso di rete
all'artefatto ufficiale in questo ambiente).

## 15.1 Cosa esiste ora (`tools/semantic_classifier/`)

- `artifact_manifest.py` — manifest di provenienza (SHA-256 reale, mai un hash passato a mano);
- `golden_qualification_corpus.py` — corpus fisso deterministico (19 esempi, inclusi edge case vuoto/whitespace/lunghissimo/emoji);
- `tokenizer_qualification.py` — TOKENIZER_GATE: carica il vero tokenizer SentencePiece, **introspeziona** (mai assume) il vero `trainer_spec.model_type`;
- `encoder_qualification.py` — ENCODER_GATE: carica il vero `.tflite` via `ai-edge-litert` (stessa famiglia LiteRT di `EmbeddingGemmaEngine.kt`), verifica shape/finite/non-zero/determinismo/pooling;
- `preflight.py` — check fail-closed completo prima di ogni lavoro costoso;
- `generate_embeddings.py` — embedding reali resumable, SOLO train/validation/test, mai blind;
- `export.py --real` — riscritto per usare manifest + qualification report (mai più un `--model-dir` grezzo), deriva il contratto encoder dai report REALI;
- `run_test_protocol.py` — valutazione TEST una tantum, si rifiuta di sovrascrivere un report esistente senza `--force` esplicito;
- `verify_candidate.py` / `build_bundle.py` / `write_receipt.py` — verifica candidato, bundle di verifica, receipt finale nel formato esatto §21;
- `run_real_training.ps1` — runner Windows a un comando che orchestra tutto quanto sopra in ordine fail-closed;
- `models/README.md` — istruzioni per l'utente su dove scaricare/posizionare i due file reali.

**Validato per davvero in questa sessione** (non solo scritto): ogni script
sopra eseguito con successo end-to-end contro un vero (ma giocattolo, non
EmbeddingGemma) tokenizer SentencePiece + un vero file `.tflite` costruiti
appositamente in sandbox (PyPI raggiungibile per `sentencepiece`/
`tensorflow`/`ai-edge-litert`, HuggingFace/Google restano bloccati) — la
pipeline completa TOKENIZER_GATE→ENCODER_GATE→preflight→
generate_embeddings→export --real→run_test_protocol→verify_candidate→
build_bundle→write_receipt gira senza errori, produce `REAL_TRAINED`+
`CalibrationStatus=PENDING`, e BLIND resta non toccato (verificato: la
funzione di generazione embedding non chiama mai `corpus.blind()`).

Vincoli (tutti rispettati per costruzione):

- fonti publisher-controlled (§7, documentato in `models/README.md`, non ri-verificabile da questo ambiente);
- niente mirror random;
- niente Conda obbligatorio (venv semplice);
- niente admin;
- niente Docker;
- niente token se non richiesto;
- credenziali mai nel repo;
- grandi artifact ignored (`.gitignore` esteso: `models/*` tranne il README, `real_run/`, `*.tflite`, `embedding_cache*.json`, i report di qualificazione, il manifest);
- resumable chunks (cache per-esempio con flush ogni 25);
- weak-PC-safe (batch=1, 2 thread di default, un'unica istanza encoder);
- frozen encoder (invariato, `train_heads.py` non tocca mai l'encoder);
- TEST non usato per tuning (`run_test_protocol.py` rifiuta un secondo run senza `--force`, testato);
- BLIND sigillato (verificato, mai chiamato).

## 15.2 Cosa resta da fare

L'unico passo mancante è l'esecuzione reale sul PC Windows dell'utente:
scaricare i due file ufficiali (§ `models/README.md`), poi
`.\run_real_training.ps1 -ModelDir ".\models" -OutputDir ".\real_run"`. Lo
script PowerShell stesso non è stato eseguito in questo ambiente (nessun
`pwsh` disponibile) — verificato solo per bilanciamento sintattico
(parentesi/quote) e per revisione manuale, stesso trattamento di ogni altro
artefatto cross-platform non eseguibile qui.

Pass 14 (e 14B) non sono completi finché questo run reale non passa.

---

# 16. PASS 15 E SUCCESSIVI — SEMANTICA

Pass 15: **Calibration + OOD + Confidence + Abstention Policy**.

Pass 16: slots, reference, follow-up, multi-domain robustness.

Pass 17: blind qualification + Honor 200 semantic acceptance.

Solo dopo una semantica stabile ha senso integrare in produzione un planner/BRAIN più agentico.

---

# 17. ORCHESTRAZIONE

Target: `ConversationalJarvisEngine` deve diventare il punto centrale.

Evitare frontend che implementano logica diversa, voice path con cervello separato, modalità guida con reasoning parallelo, Core path che bypassa la semantica, tool-calling duplicato.

```text
INPUT
→ SemanticFrame
→ ConversationalJarvisEngine
→ planner/router
→ policy
→ tool
→ FactSet/Result
→ renderer
```

---

# 18. MODELLO DI AUTORIZZAZIONE

**Il modello propone; il sistema autorizza.**

Nessun LLM deve poter eseguire direttamente side effects, shell, azioni PC, comandi sensibili, notifiche proattive o export dati.

```text
MODEL OUTPUT
→ structured proposal
→ schema validation
→ capability policy
→ state validation
→ authorization
→ deterministic executor
→ receipt
```

---

# 19. MINI CPM5-2B

Status: **CANDIDATE / QUALIFICATION REQUIRED**.

Ruolo potenziale:

- Agent Planner;
- Core BRAIN;
- structured planning.

Non deve sostituire semantic interpreter, policy, tool registry o side-effect authorization.

```text
semantic frame
→ planner/router
→ MiniCPM solo quando richiesta complessa
→ strict PlannerOutput JSON
→ Core validates
→ deterministic ToolRegistry
```

Hardware target attuale: official GGUF Q4_K_M ~1.56 GB; da qualificare su laptop debole, context iniziale realistico 4K–8K, llama.cpp/Ollama.

---

# 20. MODEL REGISTRY LOGICO

| Modello | Ruolo | Stato |
|---|---|---|
| Qwen3.5 0.8B | FAST local | ACTIVE/CURRENT TARGET |
| Qwen3.5 4B | BRAIN local weak-PC | ACTIVE/CURRENT TARGET |
| MiniCPM5-2B | planner/BRAIN candidate | CANDIDATE |
| FunctionGemma 270M | semantic competitor | CANDIDATE |
| LFM2.5-2.6B | competitor | CANDIDATE |
| LFM2.5-VL-3B | screen/UI perception | CANDIDATE |
| Gemma 3n | multimodal | CANDIDATE |
| SmolVLM | lightweight vision | CANDIDATE |
| Qwen3 embedding/reranker | retrieval | CANDIDATE |
| PaddleOCR-VL | OCR | CANDIDATE |
| Qwen3-TTS 0.6B | quality TTS | CANDIDATE/TESTED |
| Kokoro 82M | Live TTS | CANDIDATE |
| openWakeWord | wake word | PLANNED |
| Silero VAD | VAD | PLANNED |
| VibeVoice-ASR-BitNet | CPU STT candidate | CANDIDATE |
| Supertonic | TTS | PLACEHOLDER / NOT PRODUCTION |

---

# 21. HARDWARE CORE ATTUALE

Laptop:

- Lenovo 82C7 / V15-ADA;
- Windows 10;
- AMD 3020e;
- 2 core / 2 thread;
- Radeon integrata;
- 8 GB RAM installati;
- ~5.88 GB utilizzabili.

Ollama 0.33.3.

Modelli:

- `qwen3.5:0.8b` ~1 GB;
- `qwen3.5:4b` ~3.4 GB.

Target BRAIN env:

- `127.0.0.1:11434`;
- context 2048;
- threads 2;
- gpu layers 0;
- think false.

Core inference può superare 15 s, quindi streaming è prioritario.

---

# 22. FUTURO HARDWARE CORE

Target desiderato:

- i7 / Ryzen 7 moderno;
- 64–128 GB RAM;
- RTX 5070 Ti 16 GB o 16–24 GB VRAM;
- NVMe ≥2 TB.

L’architettura non deve cambiare con nuovo hardware. Devono cambiare solo implementazioni dietro interfacce: FAST model, BRAIN model, planner, STT, TTS, vision, embedding, reranker.

---

# 23. INFERENCE RESOURCE ARBITER

Il progetto deve avere un unico concetto di gestione risorse AI pesanti. Non creare un secondo “ModelResourceManager” scollegato.

```text
InferenceResourceArbiter
├─ WAKEWORD
├─ VAD
├─ STT
├─ EMBEDDING
├─ LLM_FAST
├─ LLM_BRAIN
├─ TTS_LIVE
├─ TTS_QUALITY
└─ VISION
```

Regole:

- heavy inference lease;
- deadline totale non resetta durante fallback;
- cancellation propagata;
- niente più modelli pesanti residenti insieme se non sostenibile;
- hardware non deve determinare semantica o policy.

---

# 24. LIVE VOICE ENGINE — ARCHITETTURA APPROVATA

Status: **PLANNED / DESIGN APPROVED — NON IMPLEMENTATO COME SISTEMA COMPLETO**.

Obiettivo: ottenere un’esperienza live naturale senza richiedere subito un unico speech-to-speech full-duplex model.

## 24.1 Stack v1 proposto

| Componente | Scelta |
|---|---|
| Wake word | openWakeWord |
| VAD | Silero VAD ONNX |
| STT Live | whisper.cpp Base quantizzato come baseline |
| STT Accurate | whisper.cpp Small quantizzato se benchmark sufficiente |
| Echo cancellation | WebRTC AEC3 |
| Brain | JARVIS esistente |
| Live TTS | Kokoro 82M da qualificare |
| Quality TTS | Qwen3-TTS 0.6B |
| Turn-taking | custom Live Voice Controller |
| Resource arbitration | existing InferenceResourceArbiter concept |

Whisper Small non va imposto a priori sul 3020e: scegliere Base vs Small con benchmark reale.

## 24.2 Voice architecture

```text
Microphone
    ↓
AudioDuplexEngine
    ├─ capture
    ├─ playback
    ├─ audio clock
    └─ AEC far-end reference
    ↓
WakeWord / VAD / STT
    ↓
VoiceSessionCoordinator
    ↓
ConversationalJarvisBridge
    ↓
ConversationalJarvisEngine
    ↓
Semantic / Planner / Tools / Memory
    ↓
text stream
    ↓
SpeechChunker
    ↓
StreamingTtsEngine
    ↓
AudioPlaybackEngine
```

## 24.3 Regola fondamentale voce

**La voce è un frontend, non un secondo JARVIS.** Typed input, voice input e future vision-assisted input devono convergere nello stesso orchestratore.

## 24.4 Barge-in

```text
JARVIS SPEAKING
→ mic remains active
→ AEC removes far-end
→ VAD detects user
→ short confirmation
→ cancel TTS
→ flush playback
→ STT foreground
→ correction enters same conversation context
```

Il TTS deve supportare `cancel()` in modo rapido.

## 24.5 Voice state machine

```text
SLEEPING
→ ARMED
→ LISTENING
→ FINALIZING
→ THINKING
→ SPEAKING
→ INTERRUPTING
→ LISTENING
```

Error states: `CANCELLED`, `ERROR`, `OFFLINE`.

Futuro, non v1: `BACKCHANNEL`, `UNCERTAIN`, `WAITING`, `CONTINUE`.

## 24.6 Voice metrics

Misurare:

- wakeWordLatencyMs;
- speechStartLatencyMs;
- partialTranscriptLatencyMs;
- finalTranscriptLatencyMs;
- llmFirstTokenMs;
- llmCompletionMs;
- ttsFirstAudioMs;
- ttsRealtimeFactor;
- bargeInDetectionMs;
- ttsStopLatencyMs;
- totalFirstResponseAudioMs;
- RAM peak;
- CPU peak;
- pagefile activity;
- dropped audio frames;
- AEC residual level.

---

# 25. MEMORY

Tre livelli distinti:

1. chat/session history;
2. persistent accepted facts;
3. derived retrieval structures.

Components:

- ChatStore — chat durable;
- ConversationManager — session;
- `memoria.md` / VaultRepository — accepted personal facts;
- embeddings/cache/vector index — derived;
- future graph — derived.

Regola: **memory candidate != fact**.

---

# 26. PERSONAL INTELLIGENCE

Target:

- ContextEngine produce snapshot immutabile;
- proactive governor decide se suggerire/notificare;
- modello non notifica direttamente;
- Core non notifica direttamente;
- recommendations separate da facts;
- egress policy prima della serializzazione.

Future Personal Graph: derived representation, non autoritativa, **DEFERRED** finché non serve realmente.

---

# 27. CONTEXT ENGINE

Responsabilità:

- snapshot contestuale;
- posizione;
- stato guida;
- agenda;
- health summary;
- weather;
- time;
- device state;
- activity context;
- relevant automation context.

ContextEngine non deve diventare la source of truth di ogni dominio. Deve aggregare.

---

# 28. PROACTIVITY

```text
REAL DATA
→ context snapshot
→ deterministic candidate
→ ProactiveGovernor
→ occurrence claim
→ presentation
→ delivery
```

Il modello non è proprietario della notifica.

---

# 29. OCCURRENCE RELIABILITY

Per side effects proattivi:

- occurrence identity logica;
- persistent claim;
- atomic insert/update;
- lifecycle states;
- no blind retry;
- terminal delivery state;
- debug receipt.

```text
CLAIMED
→ GENERATED
→ DELIVERY_PENDING
→ DELIVERED
```

Failure:

- `FAILED_RETRYABLE`
- `FAILED_FINAL`

`DELIVERY_PENDING` è una finestra ambigua: evitare retry cieco.

---

# 30. MORNING BRIEFING — STATO CORRENTE

## 30.1 PASSAGGIO 14.1

Commit: `e9b93edb8e020f0c6061404e533087f9a863797b`

Obiettivo: effectively-once delivery.

Aggiunto occurrence key, Room store, claim atomico, persistence, lifecycle, trigger convergence e diagnostics base. CI green, ma device test successivo ha fallito.

## 30.2 Evidenza reale Honor 200

Una mattina:

- 08:00 → briefing senza emoji;
- 08:14 → secondo briefing con emoji;
- 09:00 → terzo briefing.

Questo ha invalidato il device acceptance di 14.1.

## 30.3 Micro-patch 14.2.2 — root cause più recente

Commit principale: `df686d525feab671fb93ed865649316bdce46073`

Latest follow-up CI compile fix: `6a66d3247cff01a9dde36fb899196b22c16ff453`

Root cause trovata: `ProactiveManager.refreshMorningDigestNotification()`, chiamato da `MorningRefreshWorker` a +10/+60 minuti dopo una delivery, poteva comporre contenuto Morning-Briefing-shaped e chiamare `notifier.show()` senza nuovo occurrence claim.

`setOnlyAlertOnce(true)` non bastava: se l’utente apre/dismiss la notifica, un successivo `notify()` con stesso ID può comportarsi come un nuovo alert.

La tempistica reale combacia:

- +10 min con slack/Doze → ~08:14;
- +60 min → 09:00.

La differenza emoji non richiede per forza un renderer diverso: lo stesso composer poteva leggere weather state diverso fra 08:00 e 08:14.

## 30.4 Fix 14.2.2

Aggiunto `MorningRefreshGate.shouldRefresh(state)`: il refresh può procedere solo se l’occurrence è davvero `DELIVERED`.

Aggiunto read-only `ProactiveOccurrenceStore.peek()`.

Il refresh non compete per claim.

Aggiunto `ProactiveNotifier.show(..., silent = true)` per il refresh path: un refresh può aggiornare il contenuto ma **non deve generare un nuovo alert indipendente**.

Aggiunti bounded device diagnostic receipts.

## 30.5 Scheduler finding

Il cambio orario di micro-patch 14.2.1 non era la causa del triplo alert. `MorningTriggerScheduler` usa identità stabile per il configured-time alarm e il reschedule sostituisce l’alarm precedente.

## 30.6 Correzione storica — MorningRefreshWorker (regola §81 applicata)

**OLD CONCLUSION** (PASSAGGIO 14.1): `MorningRefreshWorker` era stato inizialmente considerato esonerato dal bug di multi-delivery.

**SUPERSEDED BY REAL DEVICE EVIDENCE + FOLLOW-UP AUDIT** (micro-patch 14.2.2): `MorningRefreshWorker` → `ProactiveManager.refreshMorningDigestNotification()` è stato confermato come l’unico bypass capace di ri-avvisare contenuto Morning-Briefing-shaped senza verifica dell’occurrence claim.

Root cause:

- briefing iniziale ~08:00;
- refresh a +10 min riemerso intorno alle 08:14;
- refresh a +60 min riemerso alle 09:00;
- `setOnlyAlertOnce(true)` insufficiente una volta che la notifica originale era già stata dismessa/letta;
- la differenza dell'emoji era spiegata da una diversa disponibilità del dato meteo nello snapshot successivo, non da un secondo composer.

Fix:

- `MorningRefreshGate.shouldRefresh(...)`;
- `ProactiveOccurrenceStore.peek()`;
- `notifier.show(..., silent = true)`;
- bounded morning delivery receipts.

La vecchia conclusione resta visibile come **SUPERSEDED**, non cancellata silenziosamente (regola §81).

## 30.7 Stato attuale Morning Briefing

```text
CODE FIX 14.2.2         IMPLEMENTED
REMOTE PUSH              VERIFIED
CI #433                  COMPLETED / SUCCESS (SHA 6a66d3247cff01a9dde36fb899196b22c16ff453)
HONOR 200 RETEST         REQUIRED
DEVICE ACCEPTANCE        NOT PASSED
```

**MICRO-PATCH 14.2.2 — riepilogo di stato**: CODE + AUTOMATED TESTS + CI CLOSED. HONOR 200 DEVICE ACCEPTANCE PENDING.

Non dichiarare il fix completamente chiuso/production-ready fino al retest reale su dispositivo.

---

# 31. MICRO-PATCH 14.2.1 — CONFIGURABLE BRIEFING TIME

UI target:

`Impostazioni → Proattività → Riepilogo mattutino → Orario briefing`

Backend già possedeva `morning_briefing_hour`, `morning_briefing_minute`, `setMorningBriefingTime(hour, minute)`.

La patch espone il setting senza creare un secondo scheduler. Cambiare l’orario non deve creare una nuova occurrence nello stesso giorno dopo `DELIVERED`.

---

# 32. PASSAGGIO 14.2 — PROACTIVE WEATHER ALERT

Final SHA: `e6b4e0baae36dd3070ca0e151bd591480cd46d56`

CI #430: SUCCESS.

Root cause del “nessun alert pioggia domani”: **MISSING_POLICY**, non regressione.

Prima `ContextEngine.onWeather()` conosceva `rainTomorrow`, ma nessun proactive consumer produceva weather alert.

## 32.1 Weather proactive flow

```text
OpenMeteoWeatherSource.fetchRain
→ RainForecast
→ ContextEngine
→ freshness gate
→ target local date
→ WeatherAlertPolicy
→ candidate
→ ProactiveGovernor
→ occurrence
→ quiet hours
→ evening worker
→ notification
```

## 32.2 WeatherAlertPolicy

`WeatherHazard`:

- `NO_ALERT`
- `RAIN_EXPECTED`
- `HEAVY_RAIN`
- `THUNDERSTORM`

`POLICY_VERSION = 1`

Structured facts only. Niente text keywords, emoji parsing o LLM judgment.

Freshness centralizzata: `WeatherFreshnessPolicy`.

---

# 33. WEATHER SOURCE OF TRUTH

Weather provider/source reale mantiene il dato. Cache derived. Policy decide cosa significano i dati. Non inventare forecast mancanti.

Grounding deve distinguere success data, stale, source failure e unavailable.

---

# 34. AGENDA

Target source model:

- Room per app-managed agenda;
- external calendar resta autoritativo per i propri record.

Home e chat devono convergere sullo stesso repository/owner. Non devono esserci due versioni dello stesso appuntamento mantenute come verità parallele.

---

# 35. HEALTH

Source: Health Connect per i dati esterni supportati.

Regole:

- range;
- coverage;
- unit;
- source attribution;
- dedup;
- stale/failure;
- permission missing.

Cache derived. Mai confondere permission missing con zero dati.

---

# 36. GROUNDING / TOOL RESULT

Grounding status standard:

- `success_data`
- `success_empty`
- `data_unavailable`
- `permission_missing`
- `stale`
- `source_failure`
- `tool_failure`
- `semantic_failure`
- `model_failure`
- `partial`
- `cancelled`
- `unknown`

FactSet separato dalla recommendation. Agenda/Health/Weather/action output devono avere controlled rendering. Mai mostrare schema grezzo all’utente.

---

# 37. TOOL REGISTRY

Target:

- tool declaration;
- capability owner;
- policy;
- authorization;
- structured input;
- structured output;
- side-effect semantics;
- receipt.

Un LLM non chiama API casuali. Passa attraverso il registry.

---

# 38. SIDE EFFECTS

```text
KNOWN NOT EXECUTED
→ retry può essere possibile

KNOWN EXECUTED
→ non ripetere

UNKNOWN
→ no blind retry
```

Vale per notifiche, messaggi, automazioni, PC Actions, Home Assistant e future external actions.

---

# 39. AUTOMATION ENGINE

Android-first.

Casi pianificati/attuali:

- buongiorno primo sblocco;
- Casa/Fuori;
- arrivo casa;
- lavoro;
- Da Francy;
- sera/notte;
- parcheggio;
- appuntamento ETA;
- trigger weather;
- contesto.

Automazione deterministica. LLM può interpretare intenti ma non deve essere l’esecutore di side effect senza policy.

---

# 40. DRIVING MODE

Funzioni target:

- navigazione;
- notifiche a tendina;
- messaggi;
- read/reply;
- music player;
- indicazioni;
- voice control;
- volume;
- Jarvis overlay.

Componenti citati: Hilt, AStarRouter, Valhalla stub, GpxReplay, DebugGps, DrivingModeScreen.

Valhalla placeholder non deve essere considerato backend production se non realmente collegato.

---

# 41. NAVIGATION

Target: navigatore offline-first molto personalizzato.

Dati:

- PMTiles;
- Lazio;
- conversioni GPKG/MBTiles;
- catalogo regioni;
- download diretto.

Route/progress/generation devono avere repository/stato autoritativo. Routing offline, map data e live traffic sono capability separate.

---

# 42. MODALITÀ ICONA

Target: overlay basso-destra, sempre disponibile quando abilitato.

Funzioni:

- wake word;
- Google Maps control dove tecnicamente consentito;
- notifiche;
- messaggi;
- risposta;
- player;
- volume;
- reminders;
- assistant actions.

Attivazione: biometria/impronta.

Mai usare overlay per bypassare policy o permessi Android.

---

# 43. PHONE CONTROL

Target finale: assistente più vicino a Siri/Gemini ma con JARVIS orchestration.

Necessari:

- Accessibility / approved Android APIs;
- capability permissions;
- deterministic action registry;
- screen perception dove necessario;
- confirmation per azioni sensibili;
- visual target != authorization.

---

# 44. SCREEN PERCEPTION

Future candidates:

- LFM2.5-VL-3B;
- OmniParser fallback;
- Gemma 3n;
- SmolVLM.

Perception output non è azione autorizzata.

---

# 45. OCR / DOCUMENT PERCEPTION

Candidate:

- PaddleOCR-VL;
- platform OCR;
- lightweight local OCR.

Scegliere in base a italiano, precisione, RAM, latency, licensing e offline feasibility.

---

# 46. UI DESIGN SYSTEM — SEGNALE

SEGNALE governa presentazione, non runtime ownership.

Direction:

- dark precise surfaces;
- localized red energy;
- no generic neon;
- no circuit wallpaper;
- no fake telemetry;
- no Material-card spam;
- no generic AI gradients;
- cinematic ma realizzabile.

## 46.1 Color tokens

- bg `#070A0E`
- surface `#10161C`
- elevated `#192129`
- borderInactive `#36414B`
- borderControl `#697884`
- borderActive `#F04452`
- redDeep `#B9273A`
- energyRed `#FF4B55`
- ambientLight `#5A1622`
- warning `#EAB866`
- success `#78CCAD`
- error `#FF9D93`
- remote `#88BFE7`
- textPrimary `#F3F5F7`
- textSecondary `#ACB7C2`
- textMuted `#8995A2`
- onEnergy `#070A0E`

Fonts: Inter, Space Grotesk, bundled offline.

## 46.2 AiCore

- 3 archi incompleti;
- dark lens;
- punto 12 o’clock.

Sizes:

- Home 112 dp;
- Chat mini 32;
- dock 48;
- driving target 64.

States:

- IDLE
- WAKE
- LISTENING
- PROCESSING
- TOOL
- SPEAKING
- SUCCESS
- ERROR
- OFFLINE
- REMOTE AI

## 46.3 Accessibility

Obbligatorio:

- TalkBack;
- font scale;
- reduced motion;
- predictive back;
- IME/insets;
- 48 dp standard;
- 56 dp primary;
- 64 dp driving.

---

# 47. SEGNALE PRIORITY PHASES

P0: tokens, typography, insets, semantics, font scale, DataStatus, runtime presentation, reduced motion.

P1: hierarchy, components, main screens.

P2: motion, listening, processing, speaking, execution geometry.

P3: cinematic polish.

Honor 200: misurare performance reale in release/profileable, target 60 fps dove pratico.

---

# 48. JARVIS MINI — HUAWEI WATCH FIT 3

Target companion: **JARVIS Mini**.

Non deve duplicare LLM, memoria, agenda o tool architecture. Ruolo: lightweight controls, status, quick interactions, companion UI. Il telefono resta owner.

---

# 49. ARCHIVIO PERSONALE

Target:

- assicurazione moto;
- appunti;
- todo;
- to-watch;
- note;
- file;
- foto;
- ricerca;
- modifica.

Modalità Pro usa locale wiki/appunti. Archivio interrogabile senza rendere la memoria derivata autoritativa.

---

# 50. OFFLINE KNOWLEDGE

Target mobile:

- Wikipedia IT/EN subset appropriato;
- manuali essenziali;
- file Vault.

Preferire corpus locale + indicizzazione + metadata + query layer + RAG, non un unico blocco ingenuo.

---

# 51. BACKUP / RESTORE

Backup dei canonical stores, non di tutte le cache derivate.

Target:

- checkpoint coerente;
- manifest;
- staged restore;
- schema version;
- secrets non portabili;
- derived caches rigenerabili;
- pending side effects non ripristinati ciecamente.

---

# 52. EGRESS POLICY

Prima di serializzare dati, inviare al Core, usare remote AI o fare fallback remoto, eseguire `EgressPolicy`.

Deve essere pure/deterministica. Read permission locale non implica export permission.

---

# 53. PC ACTIONS

Future optional capability.

```text
strict registry
→ schema
→ capability policy
→ authorization
→ execution
→ receipt
```

No shell interpolation da output LLM. No arbitrary command string.

---

# 54. SOURCE OF TRUTH MATRIX

| Dominio | Autorità |
|---|---|
| App-managed agenda | Room/repository agenda |
| External calendar | external calendar provider |
| Health | Health Connect source |
| Weather | provider response; cache derived |
| Navigation | Nav repository route/progress |
| Chat history | ChatStore |
| Session | ConversationManager |
| Accepted personal memory | Vault/memoria writer |
| Embeddings | derived |
| Graph | derived |
| Proactive occurrence lifecycle | occurrence store |
| Core model state | Core runtime/model manager |
| Protocol | jarvis-protocol |
| UI presentation | SEGNALE tokens/components |

---

# 55. PERFORMANCE / WEAK-PC POLICY

Laptop 8 GB: non mantenere contemporaneamente carichi senza motivo Qwen 4B, Qwen3-TTS, Whisper Medium, large vision, embedding model e reranker.

Preferire lease/residency controllata. Non scegliere un’architettura fragile solo per spremere l’hardware attuale.

---

# 56. TTS

Qwen3-TTS 0.6B:

- alto potenziale qualità;
- italiano supportato;
- modalità qualità;
- troppo pesante per essere imposto come Live TTS sul laptop attuale.

Kokoro 82M:

- candidato Live;
- molto leggero;
- da qualificare specificamente con voce femminile italiana.

Voce desiderata: femminile italiana, delicata, giovane, espressiva, naturale. Nome ricorrente: “Paola”.

---

# 57. STT

Laptop attuale:

- baseline Live: `whisper.cpp Base quantized`;
- Accurate: `whisper.cpp Small quantized`.

Decisione basata su benchmark reale. Non scegliere Medium/Large come default sul 3020e.

---

# 58. WAKE WORD / VAD / AEC

Wake: openWakeWord.

VAD: Silero ONNX.

AEC: WebRTC AEC3.

AEC richiede far-end PCM reference, mic near-end e coherent timing. Progettare `AudioDuplexEngine` come owner di capture/playback/audio clock/AEC reference.

---

# 59. VOICE SESSION OWNERSHIP

Un solo owner del microfono: `VoiceSessionCoordinator`.

Non:

```text
wakeword opens mic
+
STT opens another mic
+
VAD opens another mic
```

Ma:

```text
single capture stream
→ controlled fan-out
```

---

# 60. STREAMING TTS

Streaming vero:

```text
LLM tokens
→ phrase/sentence chunker
→ TTS chunks
→ playback
```

Non attendere risposta completa. Non sintetizzare parola per parola se distrugge la prosodia. `SpeechChunker` è un componente esplicito.

---

# 61. MEMORY OF MODEL CHOICES

Mai sostituire un modello senza qualification matrix.

Valutare:

- capability;
- latency;
- peak RAM;
- sustained CPU;
- context;
- structured output;
- tool-use reliability;
- Italian quality;
- licensing;
- Android/Core feasibility;
- deterministic fallback;
- device evidence.

---

# 62. CI VS DEVICE

**CI green è necessario, non sufficiente.**

Categorie che richiedono device gate:

- notifiche;
- alarms;
- WorkManager;
- Doze;
- reboot;
- battery optimization;
- audio;
- mic;
- Bluetooth;
- biometrics;
- GPS;
- Accessibility;
- overlay;
- intents;
- wake word;
- performance;
- rendering.

---

# 63. DIAGNOSTICS

Diagnostics devono essere bounded, privacy-safe, based on actual runtime e tied to request/occurrence identity.

Non loggare full prompts, full agenda, health raw payload, private messages, coordinates non necessarie o secrets.

---

# 64. BUILD / MODEL / ARTIFACT IDENTITY

Ogni prova importante deve poter rispondere:

- quale commit?
- quale APK/build?
- quale protocol version?
- quale Core version?
- quale model?
- quale artifact SHA?
- quale tokenizer SHA?
- quale dataset revision?
- quale policy version?

Senza questa prova, non chiamare un comportamento “riproducibile”.

---

# 65. ROADMAP MASTER — STRUTTURA

## FASE 0 — Architettura definitiva

- Audit ✅
- Target Architecture ✅
- Red Team / Manual ✅
- MASTER Architecture ✅ questo documento

## FASE 1 — Stabilizzazione fondamenta

Agenda, Health, Weather, grounding, structured tool results, session/reset, cancellation, automazioni, diagnostics, source-of-truth inconsistencies, backup/restore, navigation reliability, proactive reliability.

## FASE 2 — Semantic Intelligence

Tokenizer parity, encoder qualification, real embedding generation, real learned head, calibration, OOD, slots, follow-up, multi-domain, blind test, Honor test.

## FASE 3 — Orchestrazione unica

`ConversationalJarvisEngine` unificato.

## FASI SUCCESSIVE

Grounding maturity, memory, personal intelligence, context, automations, Live Voice, perception, device actions, driving, security, final acceptance.

---

# 66. IMPLEMENTATION PASS HISTORY

| Pass | Stato sintetico |
|---|---|
| 1 | structured tool results |
| 2 | grounding/presentation |
| 3 | session epoch/reset |
| 4 | Agenda shared path |
| 5 | Health range |
| 6 | CI |
| 7 | Weather |
| 8 / 8.1 | automation dedup / fail-closed |
| 9 / 9.1 | navigation generation/snapshot |
| 10–10.3 | stabilization / diagnostics / device foundations |
| 11 | stabilization |
| 12 | SEGNALE P0 |
| 12.1 | follow-up P0 |
| 13 | semantic foundation I |
| 14 | tooling ready, real artifact blocked |
| 14.1 | morning briefing occurrence reliability — automated green, device later failed |
| 14.2 | proactive weather alert |
| 14.2.1 | configurable morning briefing time |
| 14.2.2 | MorningRefresh multi-alert fix; latest device retest pending |
| 14B | real EmbeddingGemma execution handoff — Windows runner built + validated end-to-end against a toy artifact; user-PC real execution pending |

---

# 67. SHA HISTORY DI RIFERIMENTO

- Pass 1 — `1e64569...`
- Pass 2 — `cc3e400...`
- Pass 3 — `cef269...`
- Pass 4 — `4c385c...`
- Pass 5 — `9e419a...`
- Pass 6 — `87710b...`
- Pass 7 — `71db9d...`
- Pass 8 — `60009...`
- Pass 8.1 — `c0c9ee...`
- Pass 9 — `701bc...`
- Pass 9.1 — `b59fab...`
- Pass 10 — `3c2dfc...`
- Pass 10 CI — `49cec...`
- Pass 10.1 — `597791e5c714a0918f7eb86aef8276d760a5ff27`
- Pass 10.2 — `f2377808d6be977d31ce74cebe1b72326680c818`
- Pass 10.3 — `051fd3f47724749655e61a1e15d4cc0ee27827e9`
- Pass 11 — `7caf9a28da651afb4911eaa82648f173acf64f2d`
- Pass 12 — `d287a85c7124cefaacfc3d4e37127e7a0f4d1f2f`
- Pass 12 CI fix — `9f66136d59797673893716358a1f270433e2e4b4`
- Pass 12.1 — `b912793332e8a33c2b7d95eef0e4963adf5dd1c3`
- Pass 13 — `8606250cccc9a555bba8946d874d44f36bf9e1ba`
- Pass 14 tooling — `91a9bc8730c11c522736275e98b80a2d12e4461a`
- Pass 14.1 — `e9b93edb8e020f0c6061404e533087f9a863797b`
- Pass 14.2 — `e6b4e0baae36dd3070ca0e151bd591480cd46d56`
- Pass 14.2.2 primary fix — `df686d525feab671fb93ed865649316bdce46073`
- Latest follow-up fix — `6a66d3247cff01a9dde36fb899196b22c16ff453`

Nota: gli SHA abbreviati più vecchi sono storici; verificare full SHA nel repository prima di usarli come exact starting gate.

---

# 68. ASTRA — PRINCIPI ACQUISITI E ADOTTATI

## 68.1 Evidenza prima dell’architettura

Prima di modificare:

```text
construction
→ DI
→ callsite
→ consumer
→ side effect
```

Non assumere che una classe esista = feature connessa.

## 68.2 Una sola ownership per side effect

Un side effect importante deve avere logical identity, single owner, persistent lifecycle e clear terminal state.

## 68.3 No fake completeness

Non accettare mock spacciato per production, fallback prototipo spacciato per learned, model file presente ma non usato, config presente ma non letta, UI toggle che non raggiunge il consumer.

## 68.4 Failure taxonomy esplicita

Evitare `null` come rappresentazione di tutto. Distinguere nessun dato, errore, stale, permission, unavailable, partial, unknown.

## 68.5 Device acceptance come gate architetturale

Per Android, il dispositivo reale è parte del test system. MagicOS/Doze/battery manager/notification behavior non sono dettagli secondari.

## 68.6 Retry safety

UNKNOWN non è una failure normale. Per side effect: `UNKNOWN → reconcile first, never blind retry`.

## 68.7 Canonical source + derived views

Ogni duplicazione di dati deve dichiarare owner, derived or canonical, invalidation e rebuild behavior.

## 68.8 New model != architecture rewrite

Modelli sono plugin dietro contratti stabili. Vale per MiniCPM, Qwen, futuri modelli locali, TTS, STT, vision e speech-to-speech.

---

# 69. DECISION LOG — ADOTTATE

## ADR-001 — Android-first
Core potenzia ma non definisce l’esistenza di JARVIS. **ACTIVE**

## ADR-002 — Semantic interpreter separato dal Brain
Classifier/semantic layer non deve generare la risposta finale. **ACTIVE**

## ADR-003 — Event Bridge deferred
Nessun consumer reale sufficiente. **DEFERRED**

## ADR-004 — n8n/Baserow fuori dal Core attuale
Sono legacy della vecchia architettura. **REJECTED / LEGACY**

## ADR-005 — v1 protocol additive
Nessun `/v2` obbligatorio senza breaking change reale. **ACTIVE**

## ADR-006 — device acceptance obbligatoria
Test verdi non bastano. **ACTIVE**

## ADR-007 — side-effect unknown no blind retry
**ACTIVE**

## ADR-008 — SEGNALE presentation only
Renderers non possiedono dati/sensori/modelli/tools. **ACTIVE**

## ADR-009 — MiniCPM5-2B candidato planner
Non Semantic Interpreter e non executor. **CANDIDATE**

## ADR-010 — Live Voice modulare
Non PersonaPlex/Moshi/MiniCPM-o sul laptop 8 GB attuale. **PLANNED**

## ADR-011 — Voice frontend sopra stesso orchestratore
No second brain. **ACTIVE DESIGN RULE**

## ADR-012 — InferenceResourceArbiter unico
No secondo resource manager indipendente. **ACTIVE DESIGN RULE**

---

# 70. DECISIONI ESPLICITAMENTE NON ADOTTATE

- keyword routing generale;
- regex intent system;
- second semantic pipeline;
- second Morning Briefing scheduler;
- random notification IDs come dedup;
- timeout/debounce usato come correctness;
- shell arbitrary PC actions;
- full model replacement che bypassa semantica;
- forced `/v2`;
- Event Bridge senza consumer;
- n8n/Baserow come dipendenza Core;
- Supertonic stub production;
- Valhalla stub production senza verifica;
- fake tokenizer;
- synthetic embeddings spacciati per real.

---

# 71. KNOWN ISSUES / OPEN GATES

## Critical / active

### Morning Briefing
- 14.2.2 fix implementato;
- latest CI ancora da chiudere al momento del documento;
- Honor 200 strict retest richiesto.

### EmbeddingGemma
- real artifact acquisition/qualification pending — tooling and Windows
  runner now READY (§15), validated end-to-end against a toy artifact in
  this session, but the real official EmbeddingGemma acquisition/
  qualification itself has not happened yet;
- real learned head training pending — same reason;
- calibration pending (PASSAGGIO 15, untouched);
- blind pending (untouched, verified never called by the new tooling).

### Semantic device acceptance
- non chiusa.

## Important

- Core streaming latency;
- auth/TLS pairing;
- unified orchestration ancora roadmap;
- Live Voice non implementato;
- long-term PI non completo;
- device actions advanced non complete;
- full driving navigation production backend non complete.

---

# 72. DEVICE ACCEPTANCE — MORNING BRIEFING ROUND 2

Dopo 14.2.2:

1. install latest build;
2. configure briefing few minutes ahead;
3. NEXT_ALARM relevant if possible;
4. unlock near trigger;
5. exactly one alert;
6. content/emoji expected;
7. wait >90 min;
8. no second alert;
9. change configured time after delivery;
10. no same-day new briefing;
11. inspect diagnostics receipts;
12. verify refresh entries are silent;
13. reboot/process restart regression.

No “fixed” status prima di questo test.

---

# 73. DEVICE ACCEPTANCE — WEATHER ALERT

Debug fixtures:

- clear → no notification;
- rain → one rain alert;
- repeat rain → no duplicate;
- thunderstorm → one distinct alert.

Poi real provider, real evening scheduler e futura osservazione end-to-end con giorno piovoso reale.

---

# 74. QUALITY TARGET: JARVIS SENZA CORE

A progetto finito, Core OFF deve continuare:

- chat;
- essential semantic interpretation;
- reminders;
- agenda;
- Health;
- weather;
- notifications;
- geofencing;
- automations;
- driving;
- offline maps;
- phone actions;
- archive lookup;
- FAST model;
- local STT/TTS;
- wake word;
- local context;
- local proactivity;
- essential memory.

Degradano deep reasoning, large RAG, large memory retrieval, complex multimodal, heavy planner, quality TTS/STT e background reasoning.

Si fermano PC actions, Core-only large models, Core-only archives e workload impossibili su Android.

---

# 75. QUALITY TARGET: CORE ON

Core ON deve essere trasparente. L’utente non deve scegliere manualmente “manda al PC” per ogni richiesta.

Router/capability layer decide secondo availability, privacy, latency, model capability, cost, deadline e failure mode.

Android deve sempre conoscere il terminal result reale.

---

# 76. JARVIS “AGI-LIKE” DIRECTION

JARVIS non diventa più vicino ad AGI semplicemente usando un LLM più grande.

Le capacità che aumentano la generalità percepita sono:

- persistent memory;
- context;
- planner;
- tools;
- grounding;
- multimodal perception;
- autonomy con policy;
- proactivity;
- cross-session continuity;
- self-monitoring diagnostics;
- robust recovery;
- environment interaction;
- long-running objectives;
- learning from accepted preferences.

---

# 77. PROMPT CONTRACT PER CLAUDE / FUTURI AGENTI

Ogni prompt architetturale importante dovrebbe includere:

A. GOAL  
B. Current verified path / branch / SHA  
C. Existing components  
D. Target owner  
E. Semantic Impact Check  
F. Protocol impact  
G. Source of Truth  
H. Privacy / Egress  
I. Failure / fallback  
J. Migration  
K. Automated tests  
L. Device/model acceptance  
M. Diagnostics  
N. Definition of Done  
O. Do-not-claim-complete condition

---

# 78. REGOLE PER STARTING SHA

Prima di ogni grande passaggio:

- verificare remote branch HEAD;
- non usare uno SHA vecchio dalla chat;
- non assumere che stop-hook locale sia autoritativo;
- non reset/rebase automaticamente;
- se SHA differisce, fermarsi e spiegare.

---

# 79. UPDATE POLICY DI QUESTO MASTER

Aggiornare questo file quando avviene almeno uno di questi eventi:

- nuovo passaggio roadmap;
- change di ownership;
- nuova source of truth;
- nuovo model scelto;
- nuovo model rejected;
- nuova capability;
- nuovo protocol field;
- device failure;
- device acceptance;
- major security decision;
- nuova architettura voice/vision;
- deprecation;
- rollback;
- real artifact qualification;
- CI/device contradiction.

---

# 80. FORMAT DI AGGIORNAMENTO CONSIGLIATO

```text
DATE
COMPONENT
OLD STATUS
NEW STATUS
EVIDENCE
COMMIT
CI
DEVICE
ARCHITECTURAL IMPACT
SEMANTIC IMPACT
PROTOCOL IMPACT
OPEN GATE
```

---

# 81. REGOLA DI NON REGRESSIONE DOCUMENTALE

Se una nuova evidenza invalida una nota vecchia, **non cancellare silenziosamente la storia**.

Annotare old hypothesis, new evidence e new conclusion.

Esempio:

```text
MorningRefreshWorker
OLD: considered exonerated
NEW: real device + code audit proved refresh path could re-alert
STATUS: old conclusion superseded
```

---

# 82. FUTURE LIVE FULL-DUPLEX MODEL

Quando l’hardware futuro sarà disponibile, si può sostituire parte di WakeWord + VAD + STT + TTS con un vero speech-to-speech engine.

Ma non deve cambiare memory, semantic frame, planner contract, tools, authorization, context, automations, protocol concepts e source-of-truth ownership.

---

# 83. FUTURE MODEL SWAP PRINCIPLE

```text
Qwen 4B
→ MiniCPM5
→ future 7B
→ future local multimodal
```

Il cambio deve avvenire dietro `ModelProvider / BrainPort / PlannerPort`, non rifacendo gli strumenti.

---

# 84. PERSONALITY / PRESENTATION

La personalità JARVIS deve emergere da tone, concise intelligence, contextual awareness, timing, voice, visual system e calibrated behavior.

Non da fake telemetry, teatralità continua, glow, verbosity artificiale o risposte inventate per sembrare intelligenti.

---

# 85. RELIABILITY OVER CINEMATIC EFFECT

```text
correctness
> reliability
> grounding
> latency
> accessibility
> cinematic polish
```

Una UI cinematografica che notifica tre briefing è un fallimento di prodotto.

---

# 86. CORE OFF EXPERIENCE TARGET

Il passaggio Core ON/OFF deve essere uno stato normale.

UI può mostrare LOCAL, CORE, REMOTE AI, ma l’utente non deve sentirsi “senza JARVIS”. Fallback deve essere funzionale e spiegabile.

---

# 87. CORE CACHE POLICY

Core cache è derivata. Non diventa source of truth per agenda, weather, health, Android state o device notifications.

Quando Core è spento Android continua. Quando torna, re-sync con canonical source.

---

# 88. PRIVACY

Principi:

- local-first;
- minimizzazione;
- bounded diagnostics;
- egress explicit;
- secret isolation;
- no full payload nei log;
- user data non usati per training interno del classifier senza decisione esplicita;
- provenance dei dataset.

---

# 89. SECURITY FUTURA

Target:

- paired device identity;
- TLS LAN;
- scoped auth;
- anti-replay dove serve;
- capability-bound tokens;
- no open unauthenticated LAN action endpoint;
- PC actions hardened;
- audit receipts.

---

# 90. FINAL ACCEPTANCE PHILOSOPHY

JARVIS “finito” non significa “compila”.

Significa:

- architettura coerente;
- semantic robust;
- real data grounded;
- side effects safe;
- Android autonomous;
- Core optional enhancer;
- device acceptance;
- recovery behavior;
- privacy;
- no duplicate owners;
- performance realistic;
- diagnostics sufficient.

---

# 91. IMMEDIATE NEXT ACTIONS — CURRENT SNAPSHOT

Ordine consigliato:

1. CI #433 sul latest HEAD è COMPLETED / SUCCESS (§2.1) — installare latest build su Honor 200;
2. eseguire Morning Briefing ROUND 2;
3. verificare nessun re-alert +10/+60;
4. controllare diagnostic receipts;
5. se device PASS, chiudere 14.2.2 lato briefing;
6. mantenere Pass 14 artifact gate separato;
7. eseguire 14B sul PC — il runner è ora pronto (§15): `cd tools\semantic_classifier` poi `.\run_real_training.ps1 -ModelDir ".\models" -OutputDir ".\real_run"` dopo aver scaricato i due file reali per `models/README.md`;
8. solo dopo real semantic artifact gate procedere verso Pass 15;
9. Live Voice: progettazione può continuare, full implementation dopo semantic/orchestration foundation.

---

# 92. CHECKLIST PRIMA DI QUALSIASI NUOVA FEATURE

- Esiste già qualcosa di simile?
- Chi ne è owner?
- È canonical o derived?
- Passa per SemanticFrame?
- Richiede classifier change?
- Richiede protocol?
- Quale failure taxonomy?
- Quale fallback?
- Quale privacy/egress?
- Quale side effect?
- Quale idempotency?
- Quali test?
- Quale device gate?
- Quale diagnostics?
- Quale sunset per eventuale vecchio path?

---

# 93. CHECKLIST MODELLO NUOVO

- publisher;
- license;
- artifact hash;
- quantization;
- RAM;
- latency;
- Italian;
- context;
- tool use;
- JSON reliability;
- benchmark JARVIS-specific;
- fallback;
- production runtime;
- device/core target;
- security;
- no architectural duplication.

---

# 94. CHECKLIST VOICE

Prima di chiamare “Live”:

- partial STT;
- real streaming response;
- TTS chunk streaming;
- fast first audio;
- AEC;
- continuous mic;
- barge-in;
- cancellation propagation;
- turn state machine;
- single mic ownership;
- resource arbiter;
- device metrics;
- error recovery.

---

# 95. CHECKLIST PROACTIVE

Prima di inviare una notifica:

- factual source;
- fresh enough;
- deterministic policy;
- candidate;
- governor;
- occurrence identity;
- atomic claim;
- quiet-hours policy;
- side-effect state;
- receipt;
- no duplicate consumer;
- device test.

---

# 96. CHECKLIST MEMORY

Prima di scrivere una memoria persistente:

- candidate or fact?
- user intent?
- canonical writer?
- revision?
- provenance?
- overwrite rule?
- delete/update?
- cache invalidation?
- privacy?
- sync behavior?

---

# 97. CHECKLIST DEVICE ACTION

Prima di eseguire:

- exact target;
- user authorization;
- permission;
- current UI state;
- capability supported;
- side-effect safety;
- reversible?
- confirmation required?
- receipt;
- UNKNOWN behavior.

---

# 98. GLOSSARIO

**Core** — PC companion JARVIS.  
**FAST** — modello leggero e rapido.  
**BRAIN** — modello più forte per reasoning.  
**Semantic Interpreter** — trasforma input in significato strutturato.  
**SemanticFrame** — rappresentazione strutturata del significato.  
**Planner** — decide steps.  
**ToolRegistry** — elenco controllato di capability.  
**Grounding** — risposta basata su dati reali.  
**Occurrence** — identità persistente di un side effect logico.  
**SEGNALE** — design system presentation.  
**AiCore** — segno/visual state central JARVIS.  
**Egress** — dati che lasciano il device/local boundary.  
**Device acceptance** — prova su hardware reale.  
**Derived data** — dato ricostruibile da fonte canonica.

---

# 99. REGOLA FINALE

Quando c’è un dubbio fra aggiungere una scorciatoia, duplicare un sistema, introdurre keyword, creare un nuovo manager o aggirare un gate, la scelta predefinita è:

**fermarsi, tracciare il percorso reale, identificare ownership e correggere il sistema esistente.**

JARVIS deve crescere come **un unico sistema coerente**, non come una collezione di feature indipendenti.

---

# 100. SNAPSHOT DI STATO

```text
ARCHITECTURE MANUAL          AVAILABLE
TARGET ARCHITECTURE          AVAILABLE
DEEP AUDIT                   AVAILABLE
SEGNALE                      AVAILABLE
MASTER ARCHITECTURE          THIS FILE

ANDROID-FIRST                ACTIVE
CORE OPTIONAL ENHANCER       ACTIVE
SEMANTIC PIPELINE            IMPLEMENTED / NOT FULLY QUALIFIED
REAL EMBEDDINGGEMMA          BLOCKED/PENDING USER-PC RUN
PASS 14B WINDOWS RUNNER      RUNNER READY / USER-PC REAL EXECUTION PENDING
LEARNED HEAD REAL            PENDING
CALIBRATION/OOD              PENDING
BLIND                        UNTOUCHED
UNIFIED ORCHESTRATION        PLANNED
MINICPM5                     CANDIDATE
LIVE VOICE ENGINE            PLANNED
REFLEX LAYER                 PLANNED / CANDIDATES UNDER QUALIFICATION
DESERT ANT SUITE             CANDIDATE PROVIDER / NOT ARCHITECTURALLY REQUIRED
CLEAR                        CANDIDATE / LIVE LATENCY QUALIFICATION REQUIRED
REDACT                       CANDIDATE / PRIVACY-EGRESS PROTOTYPE PRIORITY
VOZ                          WATCHLIST / PLATFORM BLOCKED FOR WINDOWS-ANDROID
MORNING BRIEF 14.2.2         IMPLEMENTED / DEVICE RETEST PENDING
WEATHER PROACTIVE            IMPLEMENTED / DEVICE ACCEPTANCE PARTIAL/PENDING
CORE STREAMING               HIGH PRIORITY
PAIRING/TLS                  TARGET
EVENT BRIDGE                 DEFERRED
N8N/BASEROW                  LEGACY / NOT CURRENT ARCHITECTURE
```

---

## MAINTENANCE NOTE

Questo file deve essere aggiornato prima o insieme a ogni passaggio che modifica una decisione architetturale importante.

Se viene inserito nel repository, aggiungere a `CLAUDE.md` una regola equivalente a:

> Before substantial architectural work, read `docs/JARVIS_MASTER_ARCHITECTURE.md`.  
> Treat ACTIVE invariants as binding.  
> If current code or requested work conflicts with the document, report the conflict explicitly before modifying code.  
> Never silently create a parallel owner, second semantic path, duplicate scheduler, or alternative source of truth.

---

# 101. JARVIS REFLEX LAYER — NUOVA DECISIONE ARCHITETTURALE

Status:

**PLANNED / CANDIDATES UNDER QUALIFICATION**

Il progetto adotta il concetto di **Reflex Layer**: micro-modelli o componenti molto piccoli e specializzati possono svolgere task stretti prima di coinvolgere Semantic Intelligence, planner o BRAIN.

Questo NON introduce un nuovo orchestratore.

Il principio è:

```text
small bounded capability
→ produce bounded signal/fact
→ existing JARVIS architecture consumes it
```

Non:

```text
micro-model
→ decide global intent
→ autorizza tool
→ esegue side effect
```

Il Reflex Layer è quindi un insieme distribuito di capability leggere, non un'unica pipeline monolitica.

---

# 102. REFLEX LAYER — POSIZIONAMENTO CORRETTO

La vecchia semplificazione:

```text
Layer 0 micro-models
→ Layer 1 Semantic Intelligence
→ Layer 2 Planner
→ Layer 3 Core
```

è utile come intuizione, ma troppo rigida.

La struttura approvata è:

```text
                           INPUT
                             │
          ┌──────────────────┼──────────────────┐
          │                  │                  │
        AUDIO               TEXT              VISION
          │                  │                  │
   AUDIO REFLEXES      TEXT REFLEXES     VISION REFLEXES
          │                  │                  │
          └──────────────────┼──────────────────┘
                             ↓
                    Semantic Interpreter
                             ↓
                       SemanticFrame
                             ↓
              Fast deterministic routing
                             ↓
         ┌───────────────────┴───────────────────┐
         │                                       │
    simple capability                      complex request
         │                                       │
 deterministic tool                       planner / BRAIN
                                                 │
                                        MiniCPM / Qwen / Core
```

In parallelo esistono capability riflessive che non stanno necessariamente “prima” della Semantic Intelligence:

```text
PRIVACY REFLEXES
MEMORY REFLEXES
AUDIO PREPROCESSING REFLEXES
DOCUMENT INGESTION REFLEXES
```

Il loro punto d'integrazione dipende dal problema reale.

---

# 103. REFLEX CAPABILITY CONTRACT

Ogni micro-modello specialistico deve entrare nel progetto dietro un contratto bounded.

Ogni `ReflexCapability` deve dichiarare almeno:

- purpose;
- input contract;
- output contract;
- confidence;
- latency budget;
- resource class;
- fallback;
- owner;
- privacy impact;
- device/Core placement;
- production runtime;
- licensing gate;
- diagnostics;
- qualification status.

Regola:

**il Reflex Layer fornisce segnali, non autorità.**

---

# 104. DESERT ANT LABS — VALUTAZIONE ARCHITETTURALE

Status:

**CANDIDATE PROVIDER / NOT ARCHITECTURALLY REQUIRED**

Desert Ant Labs è interessante perché propone piccoli modelli on-device specializzati per audio, testo e visione.

Il valore strategico per JARVIS non è dipendere da un singolo vendor.

Il valore è il pattern:

```text
tiny specialized model
→ bounded result
→ escalation only when needed
```

Quindi JARVIS deve poter usare Desert Ant, ma l'architettura non deve dipendere da Desert Ant.

Un futuro replacement deve poter sostituire un modulo senza cambiare Semantic Intelligence, planner, tools, memory o policy.

---

# 105. DESERT ANT — CANDIDATE REGISTRY

| Modello | Ruolo JARVIS | Stato | Priorità |
|---|---|---|---|
| Clear | denoise / dereverb audio | CANDIDATE / QUALIFY | ALTA |
| Redact | PII detection/redaction before egress | CANDIDATE / PROTOTYPE | ALTISSIMA |
| Tongue | text language ID | CANDIDATE | MEDIA |
| Ear | spoken language ID | CANDIDATE | MEDIA-BASSA |
| Gist | topic/tag extraction for ingestion | CANDIDATE | MEDIA |
| Uhm | filler detection | CANDIDATE | BASSA |
| Align | word timestamps/alignment | CANDIDATE | MEDIA-BASSA |
| Schemer | structured extraction | CANDIDATE / BETA | WATCH |
| Voz | ASR | WATCHLIST | BLOCCATO DA PIATTAFORMA |

Altri modelli della suite vanno valutati solo quando esiste un use case concreto.

---

# 106. CLEAR — POSIZIONAMENTO

Status:

**CANDIDATE / LIVE LATENCY QUALIFICATION REQUIRED**

Ruolo potenziale:

```text
microphone
→ AEC
→ Clear
→ VAD/STT
```

Non approvare ancora:

```text
microphone
→ Clear
→ STT
```

come path realtime production senza benchmark.

Motivo:

un enhancer audio può migliorare il WER ma peggiorare troppo la latenza.

Clear deve essere qualificato su:

- laptop Windows 10 / AMD 3020e / 8 GB;
- Honor 200;
- room quiet;
- fan noise;
- TV;
- road/car noise;
- music background;
- far microphone.

Metriche:

- WER before/after;
- CPU;
- RAM;
- real-time factor;
- added latency;
- speech artifacts;
- STT first partial latency;
- end-of-turn latency.

Decisione:

**adottare solo se il beneficio reale supera il costo di latency/resources.**

---

# 107. AEC VS DENOISE — OWNERSHIP

WebRTC AEC3 e Clear hanno ruoli diversi.

```text
TTS/Speaker output ─────────────┐
                                ↓ far-end reference
Mic ─────────────────────────→ AEC
                                ↓
                         residual audio
                                ↓
                              Clear
                                ↓
                              VAD
                                ↓
                              STT
```

AEC:

- rimuove principalmente la voce di JARVIS dagli speaker;
- è fondamentale per barge-in.

Clear:

- riduce rumore ambientale e riverbero residuo.

Non usare Clear come sostituto dell'AEC.

---

# 108. CLEAR — RESOURCE POLICY

Clear non deve necessariamente essere sempre attivo.

Target:

```text
SLEEPING
→ openWakeWord + lightweight VAD

ACTIVE VOICE SESSION
→ AEC
→ optional Clear
→ VAD
→ STT
```

InferenceResourceArbiter deve poter decidere se il denoiser è attivo in base a:

- hardware;
- mode;
- latency target;
- noise profile;
- current heavy workload.

---

# 109. REDACT — PRIVACY REFLEX

Status:

**CANDIDATE / HIGH-PRIORITY ARCHITECTURE PROTOTYPE**

Redact non deve oscurare indiscriminatamente l'input prima della Semantic Intelligence.

Esempio sbagliato:

```text
USER INPUT
→ REDACT
→ Semantic Intelligence
```

Questo può distruggere informazioni necessarie per capability locali.

Target:

```text
USER INPUT
→ Semantic Intelligence
→ local capability?
    ├─ YES → use original data locally
    └─ REMOTE EGRESS
           ↓
        EgressPolicy
           ↓
        PII detector/redactor
           ↓
        remote boundary
```

Quindi Redact può diventare una capability di supporto di:

`EgressPolicy`

non un filtro globale dell'intero sistema.

---

# 110. REDACT — SECURITY LIMIT

Un PII detector probabilistico non è una garanzia assoluta di privacy.

Quindi:

```text
Redact
≠ privacy authorization
≠ egress authorization
```

È defense-in-depth.

Le decisioni di export restano di `EgressPolicy`.

I dati altamente sensibili devono poter essere bloccati per policy indipendentemente dal risultato del modello.

---

# 111. TONGUE — TEXT LANGUAGE ID

Status:

**CANDIDATE / ON-DEMAND**

Use case:

- multilingual mode;
- translation;
- uncertain language;
- mixed-language input;
- language-specific TTS/STT routing.

Non eseguire Tongue automaticamente per ogni input italiano se non serve.

Output bounded:

```text
language = it
confidence = ...
```

Non:

```text
intent = ...
tool = ...
```

---

# 112. EAR — SPOKEN LANGUAGE ID

Status:

**CANDIDATE / NON-CRITICAL**

Use cases:

- long audio;
- voice notes;
- recordings;
- multilingual sessions.

Non considerarlo il meccanismo principale per decidere la lingua entro i primi millisecondi della Live Voice session finché la latenza reale non è qualificata.

---

# 113. GIST — MEMORY INGESTION REFLEX

Status:

**CANDIDATE / PERSONAL KNOWLEDGE PREPROCESSING**

Use case corretto:

```text
new note/document
→ Gist
→ topics/tags
→ metadata
→ RAG index
```

Gist non è il Semantic Interpreter.

Non può trasformare un topic tag in autorizzazione/tool routing.

---

# 114. UHM — FILLER DETECTION

Status:

**CANDIDATE / LOW PRIORITY**

Nel Live Voice:

filler come:

- “ehm”;
- “mmm”;
- “aspetta”;
- esitazioni;

possono contenere informazioni utili per turn-taking.

Quindi non rimuovere filler prima del TurnTakingController.

Possibile uso:

```text
raw timing
→ TurnTakingController

transcript
→ optional cleanup
→ long-term transcript / notes
```

---

# 115. SCHEMER — STRUCTURED EXTRACTION

Status:

**CANDIDATE / BETA / WATCH**

Possibile use case:

```text
document/text
→ known schema
→ bounded extraction
→ typed object
```

Non sostituisce:

- SemanticFrame;
- planner;
- grounding;
- policy.

Structured extraction ≠ semantic understanding.

---

# 116. VOZ — ASR WATCHLIST

Status:

**WATCHLIST / PLATFORM BLOCKED FOR CURRENT TARGET**

Non sostituire Whisper oggi.

Target futuro:

```text
StreamingSttEngine
├─ WhisperCppEngine
└─ VozEngine
```

Quando esisterà un runtime ufficiale adatto a Windows/Android:

benchmarkare:

- Italian WER;
- latency;
- CPU;
- RAM;
- streaming behavior;
- cancellation;
- partials;
- Honor feasibility.

Il migliore resta.

---

# 117. REFLEX LAYER — NO SECOND SEMANTIC PIPELINE

Rischio da evitare:

```text
Tongue
+ Gist
+ Schemer
+ classifiers
→ hidden routing system
```

Regola permanente:

**nessun insieme di micro-modelli può diventare un secondo Semantic Intelligence stack.**

Il SemanticFrame resta la rappresentazione autoritativa del significato operativo.

---

# 118. REFLEX OUTPUT EXAMPLES

Consentito:

```text
Tongue:
language=it
confidence=.98
```

Consentito:

```text
Gist:
topics=[fitness,nutrition]
```

Consentito:

```text
Clear:
enhanced_pcm
```

Consentito:

```text
Redact:
spans=[...]
```

Non consentito:

```text
Gist says FITNESS
→ execute workout tool
```

Non consentito:

```text
Redact says SAFE
→ export automatically
```

---

# 119. REFLEX LAYER — LICENSING GATE

Prima di distribuire modelli third-party:

verificare:

- license;
- commercial terms;
- MAU/device limits;
- redistribution rights;
- model artifact rights;
- SDK license;
- attribution;
- offline bundling permission.

Nuovo gate:

**LICENSING GATE REQUIRED BEFORE PRODUCT DISTRIBUTION**

Questo vale per Desert Ant e per ogni altro vendor source-available/non-standard.

---

# 120. REFLEX LAYER — ROADMAP PRIORITY

Ordine consigliato:

1. **Redact / Privacy Reflex architecture prototype**
2. **Clear benchmark**
3. Tongue quando serve multilingual routing
4. Gist quando entra Personal Intelligence/RAG ingestion
5. Schemer monitoraggio beta
6. Voz monitoraggio Windows/Android
7. Ear/Uhm/Align solo con use case concreto

Non implementare tutti i micro-modelli perché sono disponibili.

---

# 121. REFLEX LAYER — ESCALATION PHILOSOPHY

Nuova gerarchia concettuale approvata:

```text
                 WORLD / USER
                      │
                      ▼
              REFLEX CAPABILITIES
       audio / text / vision / privacy
                      │
                      ▼
             SEMANTIC INTELLIGENCE
       intent / domain / op / slots / OOD
                      │
                      ▼
               FAST ORCHESTRATION
                      │
         ┌────────────┴────────────┐
         ▼                         ▼
 deterministic               complex planning
 capability                        │
         │                    MiniCPM / BRAIN
         │                         │
         └────────────┬────────────┘
                      ▼
               POLICY / TOOLS
                      ▼
                 REAL WORLD
```

Principio:

**escalate computation only when complexity requires it.**

Questo è particolarmente importante sul Core attuale da 8 GB.

---

# 122. REFLEX LAYER — RELAZIONE CON MINI CPM5

MiniCPM5-2B non viene “sostituito” dal Reflex Layer.

Ruoli distinti:

```text
Reflex Layer
= narrow fast signal extraction

Semantic Intelligence
= meaning normalization

MiniCPM / Planner
= multi-step planning / complex reasoning

Core
= runtime/state/policy/orchestration + heavier inference
```

L'obiettivo è evitare di svegliare il planner/BRAIN per compiti da pochi MB.

---

# 123. REFLEX LAYER — RELAZIONE CON LIVE VOICE

Live Voice v1 deve poter utilizzare Reflex capability audio:

```text
WakeWordEngine
→ AEC
→ optional denoise reflex
→ VAD
→ StreamingSTT
→ ConversationalJarvisEngine
```

Il denoise non deve essere hardcoded.

Interfaccia concettuale:

```text
AudioEnhancementEngine
├─ BypassAudioEnhancer
├─ ClearAudioEnhancer
└─ future enhancer
```

Quindi Clear è sostituibile.

---

# 124. REFLEX LAYER — RELAZIONE CON EGRESS POLICY

Privacy reflex:

```text
local user data
→ task routing
→ egress required?
   ├─ NO → original local data
   └─ YES
        ↓
     EgressPolicy
        ↓
     optional PII detection/redaction
        ↓
     allowed serialized payload
```

Redaction non precede l'autorizzazione.

La policy decide prima **se** esportare.

Il redactor può decidere **come minimizzare** il payload autorizzato.

---

# 125. REFLEX LAYER — DEVICE PLACEMENT

Preferenza:

- micro-modelli che servono al comportamento immediato del telefono → Android;
- micro-modelli che servono ingestion/desktop-heavy pipeline → Core;
- evitare doppia copia attiva se non necessaria.

Android-first invariant resta valido.

---

# 126. REFLEX LAYER — QUALIFICATION MATRIX

Ogni candidato deve essere qualificato con:

| Dimensione | Richiesta |
|---|---|
| Correctness | benchmark task-specific |
| Latency | p50/p95 |
| RAM | peak/resident |
| CPU | sustained |
| Battery | Android where relevant |
| Model size | artifact size |
| Offline | required where claimed |
| Cancellation | when streaming |
| Determinism | where relevant |
| Confidence | calibrated/useful |
| Language | Italian specifically |
| Licensing | verified |
| Failure mode | explicit |
| Fallback | defined |
| Device | Honor 200 if Android |
| Core | AMD 3020e if Windows |

---

# 127. REFLEX LAYER — SEMANTIC IMPACT CHECK

Aggiungere un micro-modello NON implica automaticamente classifier retraining.

Domande:

- produce solo un segnale ausiliario?
  - Semantic Impact: normalmente NO.

- cambia intent/domain/op/slot?
  - allora applicare Semantic Impact Check completo.

- sostituisce un parser/normalizer?
  - verificare preprocessing parity.

- introduce nuovo routing?
  - vietato senza design esplicito.

---

# 128. REFLEX LAYER — DEFINITION OF DONE

Un micro-modello è `PRODUCTION READY` solo se:

- use case reale identificato;
- owner definito;
- contratto bounded;
- fallback;
- metriche;
- privacy;
- license;
- no duplicate owner;
- automated tests;
- CI;
- real-device/core benchmark quando pertinente;
- Semantic Impact Check;
- diagnostics;
- no hidden routing.

---

# 129. MASTER CHANGELOG

## v1.3 — 2026-09-13

PASSAGGIO 14B — Real EmbeddingGemma execution handoff, tooling built and
validated end-to-end in this session (against a real toy SentencePiece+TFLite
artifact, not yet the real official model — no network access here, same
limit as PASSAGGIO 14):

- §15 rewritten: `PLANNED` → `RUNNER READY — USER-PC REAL EXECUTION PENDING`;
- new `tools/semantic_classifier/` scripts: `artifact_manifest.py`,
  `golden_qualification_corpus.py`, `tokenizer_qualification.py` (real
  SentencePiece introspection, never assumed), `encoder_qualification.py`
  (real `.tflite` via `ai-edge-litert`, same LiteRT family as
  `EmbeddingGemmaEngine.kt`), `preflight.py`, `generate_embeddings.py`
  (resumable, train/validation/test only, blind untouched),
  `run_test_protocol.py` (one-time TEST, refuses silent re-runs),
  `verify_candidate.py`, `build_bundle.py`, `write_receipt.py`;
- `run_real_training.ps1` — the Windows one-command runner;
- `embed.py`'s `real_embedder()` corrected to load the actual `.tflite`
  artifact directly (matching Android's own runtime), not a separate
  `sentence-transformers` checkpoint (kept as an explicit labeled
  alternate);
- `export.py --real` reworked to source the encoder contract from the
  real qualification reports instead of hardcoding
  `tokenizerFormat=SENTENCEPIECE_UNIGRAM`;
- §66/§71/§91/§100 updated to reflect RUNNER READY status;
- no Kotlin file touched, no Android CI impact, no Morning Briefing/
  Weather/Agenda/Health/driving/SEGNALE/Live Voice/Reflex Layer/protocol
  file touched (verified via grep before this update).

Decisione chiave: **il runner esiste e funziona, provato contro un
artefatto reale (giocattolo) in questa sessione — ma PASSAGGIO 14/14B
restano non completi finché l'utente non esegue il run reale sul proprio
PC con l'artefatto ufficiale.**

## v1.2 — 2026-09-13

Aggiornamento dello **stato verificato di MICRO-PATCH 14.2.2** (correzione documentation-only, nessuna decisione architetturale modificata):

- CI run #433 sullo SHA `6a66d3247cff01a9dde36fb899196b22c16ff453` aggiornato da `IN PROGRESS` a `COMPLETED / SUCCESS` (§2.1, §30.7, §91);
- stato sintetico portato a `CODE + AUTOMATED TESTS + CI CLOSED` / `HONOR 200 DEVICE ACCEPTANCE PENDING` — non dichiarato production-ready in assenza di retest reale;
- aggiunta la correzione storica esplicita su `MorningRefreshWorker` (§30.6, regola di non-regressione documentale §81): la conclusione originale di PASSAGGIO 14.1 (worker considerato esonerato) è marcata `SUPERSEDED` dall'evidenza reale su dispositivo + audit di follow-up di micro-patch 14.2.2, mai cancellata silenziosamente.

Nessuna modifica a Reflex Layer, Desert Ant, Live Voice, architettura semantica, stato MiniCPM, Pass 14 artifact gate, protocollo o codice runtime.

## v1.1 — 2026-09-13

Aggiunto:

- `JARVIS REFLEX LAYER`;
- Desert Ant candidate registry;
- Clear qualification policy;
- AEC vs denoise ownership;
- Redact/EgressPolicy integration;
- Tongue/Ear/Gist/Uhm/Schemer/Voz placement;
- licensing gate;
- reflex qualification matrix;
- compute escalation architecture;
- Live Voice integration contract;
- semantic non-duplication rules.

Decisione chiave:

**JARVIS adotta il pattern “specialized reflexes → semantic intelligence → planner/BRAIN escalation”, ma resta vendor-agnostic e non trasforma i micro-modelli in un secondo sistema semantico.**


**END OF JARVIS MASTER ARCHITECTURE v1.3**
