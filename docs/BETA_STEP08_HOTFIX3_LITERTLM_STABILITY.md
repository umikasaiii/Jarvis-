# BETA 8 — HOTFIX 3: stabilità LiteRT-LM (ciclo di vita dell'inferenza)

Baseline: Beta `aa3c61c` (Honor 200, Gemma 3 1B INT4, offline). Branch: `claude/beta08-hotfix3-litertlm-stability`.
Stato: **CI VERIFIED solo dopo la run CI di questo branch; NON DEVICE VERIFIED** finché l'utente non riprova sull'Honor 200.

## Sintomi riportati
1. dopo «Nuova conversazione» Gemma risponde «212121» a domande diverse;
2. dopo il riavvio le generazioni si bloccano fino al timeout;
3. cambio app/Home durante la generazione → al ritorno resta bloccata fino al timeout;
4. insieme al timeout, a volte schermata di blocco con solo PIN (riavvio non confermato);
5. batteria 16–18% (non assunta come causa; ora registrata come dato).

## Audit — risposte alle domande, con evidenza dal codice (pre-fix)
| Domanda | Risposta |
|---|---|
| `resetConversation()` può chiudere una Conversation con `sendMessage()` attivo? | **Sì.** Chiamava `conversation?.close()` senza lock (da «Nuova conversazione», dal retry di `chatReply`, da `loadLocked`). `chat()` teneva `chatMutex` ma il reset no → close nativo sotto un JNI in corso. |
| `generate()` e `chat()` possono usare lo stesso Engine senza lock comune? | **Sì.** `generate()` (usato da classificatore, interprete semantico, riassunti) non prendeva `chatMutex`. Senza modello classificatore dedicato `classifierEngine()` ricade sul motore **rapido**: una seconda `Conversation` sullo stesso `Engine` mentre la conversazione persistente era viva/in generazione. In più `activeGeneration` era uno slot singolo: la seconda chiamata falliva con `generation_already_running` e `cancel()` raggiungeva una sola delle due. |
| Classificatore e motore rapido possono generare in concorrenza? | Sì, stessa causa (vedi sopra); il percorso classificatore→chat è sequenziale nel turno, ma «Nuova conversazione»/Stop/worker diversi no. |
| Dopo timeout/cancel il nativo è davvero libero prima del turno successivo? | Solo se `sendMessage` tornava. La cancellazione della **coroutine** (scope Activity/Worker fermato) non chiamava `cancelProcess()`: il JNI restava in corso fino al watchdog (90 s) tenendo il lock; i turni successivi aspettavano 4 s e fallivano → «bloccata fino al timeout». Nessun rilevamento di un nativo che ignora `cancelProcess()`. |
| Background/foreground, process death, riapertura? | La generazione gira nel `CoroutineWorker` (foreground service dataSync), non nel ViewModel: lo scope UI non la ferma. Il risultato è in Room (`output`) e la chat si ripristina da lì. Lacune trovate: righe `GENERATING` **orfane** dopo un process death facevano attendere ogni messaggio nuovo fino a 120 s (`olderActiveCount` senza nozione di staleness). |
| «212121» viene da Gemma o da una pipeline precedente? | **Non c'è alcun codice che possa produrlo**: la stringa non esiste nel repo; `AssistantReplyCleaner` rimuove soltanto etichette/continuazioni; la risposta di un task già completato (`existingReply`) è per `taskId`, quindi non si ripete tra domande diverse. L'unica sorgente possibile è l'output grezzo del modello. **Causa del degrado: ipotesi non provata** — coerente con uno stato nativo danneggiato da una `Conversation` chiusa/annullata sotto uso (punto 1) o con due conversazioni sullo stesso Engine (punto 2). Non verificabile senza il dispositivo; ora la diagnostica registra da quale transizione nasce (`from_reset`, `from_timeout`, `from_cancel`…). |
| Crash nativi, OOM, blocchi JNI, WorkManager? | Non riproducibili qui (nessun SDK/modello). Ora catturati dalla diagnostica: `ApplicationExitInfo` del processo precedente (CRASH_NATIVE, LOW_MEMORY, SIGNALED, ANR…), memoria disponibile, `WEDGED` quando il nativo non rientra dopo uno stop. |
| Schermata PIN = riavvio reale? | **Non si assume.** Diagnostica: contatore `boot_count` di sistema confrontato col precedente (`device_rebooted_since_last_run` / `same_boot`), uptime, eventi SCREEN_OFF/SCREEN_ON/USER_PRESENT, motivo di uscita del processo precedente. |

## Correzione (architettura)
- Nuovo modulo puro `:core` `core/llm/InferenceSlot` (+ `NativeInference`, `DegenerateOutput`, `InferenceEventLog`) = **unico proprietario**
  del ciclo di vita di un Engine nativo. `LitertLmEngine` è ora solo l'adattatore Android.
  1. **Corsia singola**: `chat` e `once` (generate/stateless) sono serializzati da un unico lock; attesa limitata (8 s chat, 3 s helper) → `null`, mai attesa infinita.
  2. **Mai chiudere ciò che è in uso**: `reset()`/`shutdown()` non chiudono se una chiamata è in corso; la chiusura nativa è differita al proprietario della corsia **dopo** il ritorno di `send()`. `epoch` cambia subito, così il coordinatore ricostruisce il contesto.
  3. **Lo stop è reale**: cancellazione della coroutine chiamante, `cancel()` e watchdog raggiungono `cancelProcess()`; uno stop richiesto mentre la chiamata è ancora in coda la annulla invece di perdersi.
  4. **Nessuno stato avvelenato**: dopo cancel/timeout/errore/output degenere la conversazione persistente è scartata (non riusata). Una risposta degenere (`212121`, loop di token) è scartata e **ritentata una volta** su conversazione nuova; se degenera ancora → `null` + messaggio di fallimento sicuro del coordinatore.
  5. **Wedge**: se il nativo non rientra entro 10 s da uno stop, lo slot lo segnala e l'engine viene **sostituito** (reload dello stesso modello) invece di restare bloccato; il vecchio engine è chiuso se/quando rientra.
- «Nuova conversazione» ora annulla prima l'eventuale risposta in corso (nativo + righe in coda) e poi resetta.
- Coda: una riga attiva non aggiornata da >5 min (orfana dopo process death) non blocca più i messaggi nuovi (`QueueLiveness`, `olderActiveCount(..., staleBefore)`).
- Nessun wake lock, nessun permesso nuovo, nessun workaround alle protezioni Android; il Worker resta un foreground service dataSync.

## Diagnostica (nessun contenuto)
`llm/InferenceDiagnostics` → file `files/diag/llm_events.log` (max 256 KB) + Logcat `JarvisLlmDiag` + schermata Diagnostica › «Diagnostica inferenza LLM».
Eventi: LOAD_*, CHAT_START/END (ms), ONCE_*, CONV_OPENED/DISCARDED, RESET_APPLIED/DEFERRED, CANCEL_REQUESTED, TIMEOUT, BUSY, WEDGED, DEGENERATE(+tag sorgente), APP_FOREGROUND/BACKGROUND, SCREEN_*, USER_PRESENT, PROCESS_START(boot_count, uptime), PREVIOUS_EXIT(motivo), MEMORY(mem libera MB, batteria %). Il tipo `InferenceEvent` non ha campi di testo libero: prompt/risposte non possono entrarvi per costruzione (test dedicato).

## Test — chi prova cosa
| Tipo | Cosa | Dove |
|---|---|---|
| **JVM, fake engine** (nessun modello, nessun Android) | reset durante generazione; classifier↔chat mai concorrenti (12 chiamate miste → max 1 in nativo); timeout poi nuova richiesta; cancel poi riuso; stop in coda non perso; cancellazione scope chiamante → `cancelProcess`; generazione di un worker non fermata dal teardown di un altro scope; wedge; shutdown differito; output degenere («212121») con sorgente in diagnostica e senza contenuto | `core/.../InferenceSlotTest`, `DegenerateOutputTest`, `QueueLivenessTest` |
| **JVM, guard statici** | cablaggio Android (nessun close diretto, ogni entry point passa dallo slot, recovery wedge, «Nuova conversazione» annulla prima, nessun wake lock) | `app/.../InferenceLifecycleGuardTest` |
| **Emulatore (Room reale)** | riga attiva sopravvive a chiusura/riapertura DB (process death); riga orfana non blocca, riga viva sì | `AssistantQueueRecoveryInstrumentedTest` (workflow `db-migration-tests.yml`) |
| **Modello reale** | **nessuno in CI** (Gemma non è nel repo/CI). | — |

## Cosa NON è provato (onestà)
- Che LiteRT-LM reale rispetti il contratto assunto in `NativeConversation` (cancel da altro thread; più `Conversation` per `Engine`): non verificato (SDK/AAR non raggiungibili da questo ambiente). Lo slot non dipende dalla seconda ipotesi per la sicurezza (una sola chiamata alla volta), ma la conversazione persistente e quelle «once» possono ancora coesistere come prima del fix.
- Che la causa di «212121» sia quella ipotizzata; che l'app in background non sia rallentata da MagicOS; che la schermata PIN sia correlata: **solo il dispositivo può dirlo** — la diagnostica serve a questo.
- Riavvio WorkManager reale dopo kill, comportamento foreground service con Activity in background: solo prova fisica.
