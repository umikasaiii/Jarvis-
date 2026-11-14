# JARVIS — ROADMAP CANONICA PER CATEGORIE E MACRO-STEP

**Revisione documentale:** 2026-11-14 · **Stato:** DOCUMENTATION ONLY / nessuna implementazione autorizzata da questa revisione.

## Regola aggiornata di conteggio

Su autorizzazione dell'utente, il limite storico **30 macro-step** (§65.7 del Master) è **SUPERATO** da questa mappatura in **50 macro-step distribuiti in 10 categorie**. I 30 sono mantenuti solo come convenzione storica: non erano mai stati mappati canonicamente e non costituiscono quindi un insieme di ID da rinumerare. La numerazione storica dei PASSAGGI 14/14B/15, BETA 1–8 e Guardian G1–G7 **NON cambia**.

**Categorie A–H:** percorso di stabilizzazione Android e progressiva espansione autorizzata; **I**: Core PC opzionale, **non blocca Android**; **J**: estensioni post-release, bloccate fino ai gate cumulativi. Numeri del tipo `A07` identificano macro-step di pianificazione, NON commit, test, tempi stimati, permessi o stato di implementazione.

**Fonti riconciliate (rami distinti, NESSUN merge di codice):**
- Ultimo ramo BETA/Knowledge analizzato: `claude/beta-knowledge-engine`, build `22c15e7`, documenti `docs/BETA_STEP08_HOTFIX5_QUALITY_ENGINE.md`, `docs/KNOWLEDGE_ENGINE_ZIM.md`, `docs/BETA_FINAL_HONOR200_TEST_PLAN.md`.
- Backlog Guardian e future estensioni: `claude/guardian-g1-g2` commit `447e7042`, Master §65.9–§65.14, 64 candidati raggruppati in 8 fasi, 3 moduli post-release, addendum migrazione/recovery, Coding Studio/Agente Live.
- A0–A4 e Core B: sezioni §65.1–§65.6 condivise per origine. Le due versioni del Master restano rami documentali divergenti finché non si riconciliano i contenuti, senza merge automatici.

## Ownership, stato e dipendenze

- **Non duplicare componenti autoritativi:** `LlmRouter`/`InferenceSlot`, `ChatTurnRunner`, ToolRegistry/ToolPolicy, scheduler, memoria, `KnowledgeRepository`, vault, backup, `VpnService`, orchestratore A3 e Guardian. Quando un elemento ricompare in più sezioni, la seconda è **estensione** della prima, non nuova implementazione parallela.
- Stati differenti non sono equivalenti: `CODE PRESENT`, `CI VERIFIED`, `DEVICE VERIFIED`, `PRODUCTION READY`. Una BETA pubblicata o un test JVM verde non dimostra la funzionalità sul telefono. Non stimare percentuali globali dal rapporto macro-step completati: i macro-step hanno dimensioni diverse.
- **Priorità immediata:** rileggere i file ZIM 2026 sul vero Honor 200 con build successiva a `e9ddd5a`; poi grounding/FTS, stabilità chat e test finali A/B/C. Nessun passo della categoria J si avvia in questa fase.
- **Gate post-release J:** richiedono completamento funzioni principali e Beta, A0–A4, Guardian G1–G7, verifica dell'affidabilità reale, sicurezza e nuova autorizzazione esplicita; le capacità H già deliberate da completare prima sono prerequisiti secondo il Master. Il Core I resta enhancement opzionale, a meno che uno specifico sottocomponente futuro sia volontariamente definito Core-only.
- **Distinguere preparazione, implementazione e qualifica:** un modulo G1 `DOCUMENTED` non conta come codice funzionante, G2 `INITIAL CI` non equivale a G3. I moduli J sono esclusivamente roadmap.

## Registro completo delle categorie e dei 50 macro-step

### A. BETA, affidabilità e fondamenta offline Android (8 macro-step)

**Gate / collocazione:** Ora: stabilizzazione e collaudo BETA isolata.

| ID | Macro-step | Stato comprovato / prudenziale | Ambito e riferimento |
|---|---|---|---|
| `A01` | Identità APK BETA, sicurezza build e integrità dati | CI VERIFIED; DEVICE PARTIAL | BETA 1/8, isolamento e migrazioni |
| `A02` | Chat locale LiteRT-LM e stabilità dei turni | CI VERIFIED; QUALITY DEVICE FAIL | BETA 2/8 + Hotfix 3/4/5; qualità Gemma 1B ancora insufficiente |
| `A03` | Note, attività, archivio e memoria personale | CI VERIFIED; DEVICE PENDING | BETA 3/8 |
| `A04` | Document RAG e risposte su file personali | CI VERIFIED; DEVICE PENDING | BETA 4/8 |
| `A05` | Traduttore offline, testo e voce | CI VERIFIED; DEVICE PENDING | BETA 5/8 |
| `A06` | Offline Hub: Kiwix, Organic Maps e integrazioni | CI VERIFIED; DEVICE PENDING | BETA 6/8; non equivale a risposta AI basata su ZIM |
| `A07` | Knowledge Engine: lettura ZIM, ricerca titoli, citazioni | CI VERIFIED; DEVICE PENDING | BETA e9ddd5a→22c15e7; lettura ZIM moderni da ritestare fisicamente |
| `A08` | BETA 7/8 e 8/8: affidabilità quotidiana, restore, A/B/C Honor e GO | CI VERIFIED; DEVICE GATES OPEN | Notifiche, boot, backup/ripristino, prove 3 giorni; non dichiarare BETA chiusa |

### B. Conoscenza, intelligenza locale e orchestrazione (6 macro-step)

**Gate / collocazione:** Base Android autonoma; semantica dopo gate specifici.

| ID | Macro-step | Stato comprovato / prudenziale | Ambito e riferimento |
|---|---|---|---|
| `B01` | Indice offline persistente e ricerca FTS/semantica sui contenuti | PLANNED | SQLite FTS incrementale, indicizzazione on-device, ricerca oltre titoli; nessun secondo knowledge store |
| `B02` | Grounding fattuale, controllo evidenze e benchmark con modelli reali | PARTIAL; DEVICE FAILED/PENDING | Citazioni/supporto ai fatti, errori, recall, fonti mancanti, casi holdout |
| `B03` | Modelli FAST/ADVANCED locali e confronto prestazioni reali | CANDIDATE / DEVICE PENDING | Qwen 3.5 4B solo se formato/runtime/risorse Honor compatibili; Gemma 1B fallback rapido |
| `B04` | Semantic Pass 14B reale e learned head | PAUSED; PC TOOLING REQUIRED | Tooling PC dell'utente per qualifica del modello, non Core come dipendenza Android |
| `B05` | Pass 15 calibration/OOD, BLIND, riferimenti e semantica Honor | PLANNED; BLOCKED BY B04 | Gate semantici originari non superati |
| `B06` | A3 Unified Orchestration + PlannerPort canonico | PLANNED; BLOCKED BY B05 | Unico motore conversazionale/orchestratore; LLM non autorizza né esegue direttamente side effect |

### C. Proattività e automazioni persistenti (4 macro-step)

**Gate / collocazione:** A0 prima della maturità A4.

| ID | Macro-step | Stato comprovato / prudenziale | Ambito e riferimento |
|---|---|---|---|
| `C01` | Morning Briefing: Work Package E, NEXT_ALARM e FIRST_UNLOCK | DEVICE FAILURE/RETEST PENDING | Consegna affidabile su MagicOS, anti-duplicazione e 3 mattine |
| `C02` | Persistent Agent Kernel PA-2 / Morning Assistance | PARTIAL CI; PA-2 NOT STARTED | Riutilizzare PA-1A/1B; eventi, state machine e receipt |
| `C03` | Convergenza automazioni, reminder, scheduler e reboot | PARTIAL; DEVICE PENDING | Un solo proprietario scheduler; no duplicazione della coda BETA |
| `C04` | Responsabilità contestuali estese | PLANNED | Appointment, rain, evening, driving, backup health, home arrival dopo chiusura Morning |

### D. Live Voice, conversazione e percezione (4 macro-step)

**Gate / collocazione:** A2 indipendente nei limiti; completamento in A4.

| ID | Macro-step | Stato comprovato / prudenziale | Ambito e riferimento |
|---|---|---|---|
| `D01` | Live Voice foundation 0.x | PARTIAL CI; DEVICE PENDING | Fasi iniziali 0.1–0.6 CI; successive VAD/AEC storiche da qualificare |
| `D02` | Streaming reale token LLM → TTS | NOT IMPLEMENTED | No fake streaming o doppio brain |
| `D03` | VAD/AEC, full duplex, interruzione e barge-in | PARTIAL; DEVICE PENDING | Voice 0.7–0.9 e LV-R1; prove microfono, Bluetooth e latenza |
| `D04` | Percezione/sensori, traduzione live e multimodalità | PARTIAL / PLANNED | Permessi, risorse e differenza fra feature già presente e capacità futura |

### E. Azioni Android, esperienza utente e A4 (4 macro-step)

**Gate / collocazione:** Completamento Android prima della piattaforma avanzata.

| ID | Macro-step | Stato comprovato / prudenziale | Ambito e riferimento |
|---|---|---|---|
| `E01` | Tool Android sicuri e integrazioni con app/dispositivi | PARTIAL / PLANNED | ToolRegistry/Policy, permessi Android, conferma per azioni sensibili |
| `E02` | Guida, navigazione, geofence e automazioni contestuali | PARTIAL; DEVICE PENDING | Organic Maps/driver mode reali, niente privilegi impliciti |
| `E03` | UI, SEGNALE, dashboard, bolla e modalità Pro | PARTIAL / PLANNED | Unificare presentazione; nessun secondo stato del sistema |
| `E04` | A4 maturità e accettazione end-to-end Honor 200 | PLANNED; DEVICE GATE OPEN | Reflex, memoria/intelligenza personale, percezione, affidabilità e UX |

### F. Sicurezza dei dati, hardening e recupero (4 macro-step)

**Gate / collocazione:** Requisiti critici prima di MAIN APP GO; resto in A4/advanced.

| ID | Macro-step | Stato comprovato / prudenziale | Ambito e riferimento |
|---|---|---|---|
| `F01` | Strong authentication, vault e gestione chiavi | PLANNED / PARTIAL | Keystore, biometria, policy dati sensibili; riutilizzo unico vault |
| `F02` | Backup cifrato, recovery key, migrazione e disaster recovery | PARTIAL; DEVICE PENDING | Prove restore negativo, telefono perso/distrutto, ripristino pulito; nessuna perdita dati |
| `F03` | AppSec, dependences, firma release e security gate CI | PARTIAL / PLANNED | OWASP MASVS/MASTG, supply chain, audit native, hardening |
| `F04` | Security & reliability review trasversale | PLANNED | P0/P1, audit indipendenti dove possibile, test di resilienza/offline |

### G. JARVIS Guardian — device e rete (6 macro-step)

**Gate / collocazione:** Completare G1–G7 e qualifica prima di moduli post-release.

| ID | Macro-step | Stato comprovato / prudenziale | Ambito e riferimento |
|---|---|---|---|
| `G01` | G1: threat model e architettura Guardian | DOCUMENTED | No security score inventati |
| `G02` | G2: dashboard, controlli Android read-only, hashing file | INITIAL CI VERIFIED; DEVICE PENDING | Observer ≠ enforcer; non protezione globale |
| `G03` | G3: firewall VPN locale, DNS/AdBlock e controllo traffico | PLANNED | Un solo VpnService, consentimento esplicito, verifica packet path |
| `G04` | G4: WireGuard, tunnel privato e DNS/IPv6 leak protection | PLANNED | Server VPN reale, gestione chiavi e fail-safe |
| `G05` | G5: Tor Browser ufficiale e navigazione privata | PLANNED | No Tor proprietario, no promessa anonimato |
| `G06` | G6 audit + G7 accettazione Guardian su Honor 200 | PLANNED | Network fault injection, app perms, batterie, test sicurezza reali |

### H. Piattaforma avanzata — backlog 64 capacità in 8 fasi (8 macro-step)

**Gate / collocazione:** Solo dopo BETA + A0–A4 e gate di sicurezza; nessun modulo duplicato.

| ID | Macro-step | Stato comprovato / prudenziale | Ambito e riferimento |
|---|---|---|---|
| `H01` | FASE 1 — Sicurezza delle fondamenta avanzata | PLANNED / DEFERRED | Approfondire F, non creare secondo vault/CI |
| `H02` | FASE 2 — Security Center, privacy/monitoring e alert | PLANNED / DEFERRED | Estendere Guardian G2/G3, non un secondo Security Center |
| `H03` | FASE 3 — Network Shield | PLANNED / DEFERRED | Estendere Guardian G3/G4: AdBlock, firewall, VPN privata |
| `H04` | FASE 4 — Secure Device Network | PLANNED / DEFERRED | Mini server, dashboard web, pairing, file sharing e smart home autorizzati |
| `H05` | FASE 5 — Cybersecurity Toolkit di base | PLANNED / DEFERRED | APK analyzer, appsec, reverse engineering; premessa di Cyber Lab Pro |
| `H06` | FASE 6 — AI Security Intelligence | PLANNED / DEFERRED | Analisi log/report/threat intelligence e strumenti autorizzati |
| `H07` | FASE 7 — JARVIS OS/Launcher/Plugin | PLANNED / DEFERRED | Launcher non è nuovo OS; AOSP solo ricerca separata |
| `H08` | FASE 8 — Advanced Security Platform + Coding Studio + Agente Live | PLANNED / DEFERRED | Testing continuo e tool isolati; Coding Studio/Agente Live sono sottoprogetti dedicati §65.9 |

### I. Core PC opzionale e interoperabilità (3 macro-step)

**Gate / collocazione:** CORE OFF = Android autonomo; questi step NON bloccano la release Android/post-release Android-only.

| ID | Macro-step | Stato comprovato / prudenziale | Ambito e riferimento |
|---|---|---|---|
| `I01` | Pairing TLS, protocollo, interfacce e trust tra dispositivi | DEFERRED | Core opzionale, no bypass autorizzazioni Android |
| `I02` | Brain/modelli più grandi, RAG pesante, STT/TTS sul PC | DEFERRED | Solo potenziamento, mai prerequisito delle funzioni dichiarate offline |
| `I03` | Azioni PC, automazioni/dispositivi remoti e servizi locali | DEFERRED | No dipendenza per responsabilità critiche, verifica autorizzazioni |

### J. POST-RELEASE — estensioni dopo chiusura e verifica globale (3 macro-step)

**Gate / collocazione:** Soltanto dopo BETA, A0–A4, Guardian G1–G7, espansioni approvate pertinenti e device acceptance; nuova autorizzazione.

| ID | Macro-step | Stato comprovato / prudenziale | Ambito e riferimento |
|---|---|---|---|
| `J01` | JARVIS Autonomous Agents 100% offline | POST-RELEASE; NOT STARTED | Planner/Executor/Verifier/Recovery logici, modello condiviso e azioni autorizzate; estensione di B06/C02 |
| `J02` | Guardian IP Geolocation & Geo-Select | POST-RELEASE; NOT STARTED | Estendere G03/G04/G05, non secondo VPN; paese solo via endpoint reali |
| `J03` | JARVIS Cyber Lab Pro | POST-RELEASE; NOT STARTED | NetHunter Rootless ARM64, Nmap, Nuclei, CVE/KEV, analista AI, audit autorizzati; estensione H05 |

## Collocazione corretta delle aggiunte recenti

| Richiesta | Posizione primaria nel nuovo indice | Altre dipendenze / niente duplicazioni |
|---|---|---|
| Hotfix 3/4/5 chat, Quality Engine | A02 | B02/B03 sono qualifiche e sviluppi di accuratezza, non altri motori |
| Lettore ZIM e ZIM Hotfix moderni | A07 | B01 indice FTS separato, B02 evidenze; nessun secondo KnowledgeRepository |
| Recovery, backup e strong authentication | F01–F04 | Test minimi già gate A08 prima MAIN APP GO; non nuovo vault |
| Guardian G1–G7 | G01–G06 | H02/H03 ampliano G, non nuovo firewall/VpnService |
| 64 candidate e 8 fasi di espansione | H01–H08 | 64 feature restano sotto-task di 8 fasi, NON 64 nuovi macro-step |
| Coding Studio / Agente Live | H08 (sottoprogetti autonomamente tracciati) | Base in B06/E01; distinti per capability e UI, policy unica |
| Autonomous Agents 100% offline | J01 | Estende B06+C02, non crea un secondo planner o motore |
| IP Geo-Select / geolocalizzazione IP | J02 | Estende G03/G04/G05; posizione IP ≠ GPS; server VPN reali |
| Cyber Lab Pro NetHunter/Nmap/Nuclei/CVE/AI | J03 | Estende H05/H06, riutilizza Guardian e agente condiviso |
| Core PC enhancement | I01–I03 | Optional; non blocca l'Android offline-first |

## Regole per futuri aggiornamenti

1. Ogni nuova richiesta va sotto un macro-step esistente quando ne estende ownership o output; un nuovo macro-step nasce soltanto se esiste un **deliverable autonomo**, nuovo gate di verifica e dipendenza chiara. La soglia **non è più 30** e non si deve gonfiare il numero per motivi cosmetici.
2. Ogni stato va accompagnato in seguito da commit, test CI, prova dispositivo e limiti ove disponibili. Se cambia solo il prompt o il codice non si può segnare `DEVICE VERIFIED`.
3. Versionare questo registro sul ramo che porta gli ultimi test BETA; le specifiche dettagliate Guardian restano nel ramo Guardian fino alla riconciliazione documentale definitiva. Evitare merge ciechi tra rami applicativi.
4. Non spostare la fase post-release prima delle categorie e gate già approvati; non far dipendere la piena operatività Android dal Core opzionale.
5. Conservare i dettagli tecnici e i limiti legali su Cyber Lab, VPN, Tor e agenti; nessuna promessa di sistemi impossibili da attaccare.

**Cambiamento documentale soltanto.** Nessun file di codice, APK, workflow o release deve essere modificato dall'adozione di questo registro.
