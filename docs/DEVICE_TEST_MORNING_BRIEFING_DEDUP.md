# Device test checklist — PASSAGGIO 14.1 Morning Briefing Delivery Reliability

Pacchetto di accettazione dispositivo per PASSAGGIO 14.1 (effectively-once
delivery del briefing mattutino). Nessuna infrastruttura Robolectric/
instrumented esiste in questo repository — tutto ciò che segue va eseguito
sull'HONOR 200 reale (o un dispositivo/emulatore equivalente).

**Stato atteso legittimo**: `AUTOMATED=PASS, CI=PENDING, DEVICE=PENDING` per
ogni riga finché questa checklist non è stata eseguita davvero — non è un
fallimento, è lo stato onesto di un fix la cui parte automatizzata (core +
store JVM test) è già verde.

## Identità build

- Commit HEAD al momento della scrittura: vedi `Commit SHA` nel report OUTPUT
  di PASSAGGIO 14.1 in questa stessa conversazione.
- `BuildConfig.BUILD_ID` (Diagnostica › card "Ultima risposta chat") deve
  combaciare con la SHA a 7 caratteri di quel commit prima di iniziare.

## Come annotare ogni passo

Per ogni scenario sotto: apri Diagnostica prima e dopo (pannello "Proattività
(briefing)", già esistente — mostra l'ultimo esito di `run()`; nessuna nuova
UI aggiunta in questo passaggio per l'occorrenza — l'unico segnale
osservabile in-app resta questo pannello + il numero di notifiche mattutine
effettivamente comparse). Segna PASS/FAIL/PENDING per ogni riga.

---

## A — Mattina normale

1. «Automazioni in background» attivo, sveglia impostata dopo le 5:00.
2. Sblocca il telefono al mattino come al solito.
3. Atteso: **esattamente UNA** notifica "Buongiorno" con emoji meteo, nessun
   duplicato nei minuti/ore successive (incluso il refresh +10/+60min: quello
   deve **aggiornare la stessa notifica**, mai aggiungerne una seconda).

## B — Apertura app vicino all'orario pianificato

4. Poco prima dell'orario configurato (`morningBriefingHour`/`Minute`,
   default 8:00) o dell'orario next-alarm+offset, apri l'app manualmente
   (questo NON è un vero sblocco-schermo, ma può comunque far scattare un
   trigger periodico/di sistema).
5. Atteso: nessuna consegna doppia — se il briefing era già stato consegnato
   da un trigger a orario, l'apertura app non ne genera un secondo.

## C — Primo sblocco vicino all'orario pianificato

6. Vicino all'orario CONFIGURED_TIME o NEXT_ALARM, sblocca il telefono per la
   prima volta della giornata (vero `ACTION_USER_PRESENT`).
7. Atteso: **una sola** consegna, indipendentemente da quale dei due trigger
   (l'alarm a orario o lo sblocco reale) arriva per primo — l'altro deve
   vedere `AlreadyOwned` e non fare nulla.

## D — Kill/relaunch dell'app

8. Subito dopo la consegna del mattino, forza la chiusura dell'app (kill dal
   task switcher) e riaprila.
9. Atteso: nessuna nuova consegna al riavvio — l'occorrenza è già `DELIVERED`
   e persiste nel DB, non nella sola memoria di processo.

## E — Reboot prima dell'orario pianificato

10. Riavvia il telefono PRIMA dell'orario configurato/next-alarm di quel
    giorno.
11. Atteso: dopo il boot, gli alarm/i trigger vengono ri-registrati
    (`scheduleAll()`/boot receiver esistenti) e il briefing arriva comunque
    **una sola volta** all'orario giusto — nessuna doppia registrazione del
    worker/alarm che produce due consegne.

## F — Fallimento temporaneo e retry

12. Se possibile riprodurre un fallimento a metà pipeline (es. disattivare
    momentaneamente la rete durante la generazione, se il contenuto dipende
    da un fetch remoto) durante la finestra di generazione del briefing.
13. Atteso: un fallimento pre-consegna rilascia il claim come
    `FAILED_RETRYABLE` (mai bloccato permanentemente) — un trigger
    successivo (es. il refresh +10/+60min, o un nuovo sblocco) può
    completare la consegna; MAI due consegne per lo stesso fallimento.

## G — Attesa 45 minuti, nessun duplicato ritardato

14. Dopo la consegna del mattino, aspetta ~45 minuti senza toccare il
    telefono (finestra che copre sia il refresh +10min sia buona parte del
    percorso verso il +60min).
15. Atteso: nessuna seconda notifica "Buongiorno" separata — il refresh
    +10/+60min aggiorna silenziosamente la STESSA notifica (stesso id
    stabile) con dati freschi, senza mai ri-parlare il briefing né crearne
    una seconda voce visibile nello shade.

---

## Onestà — Honor 200 acceptance (PASSAGGIO 14.1)

Nessuno degli scenari A-G è stato eseguito da questo ambiente (nessun
dispositivo Android disponibile qui). Non dichiarare PASS su nessuna riga
finché non è stata eseguita realmente sul dispositivo.

---

# ROUND 2 — MICRO-PATCH 14.2.2: REAL DEVICE FAILURE + FIX

**Honor 200 acceptance eseguita davvero, e FALLITA.** Il testo di §A/§G sopra
("il refresh +10/+60min aggiorna silenziosamente la STESSA notifica... senza
mai... crearne una seconda voce visibile nello shade") era un'assunzione
**mai verificata su dispositivo reale** — ed era sbagliata.

## Cosa è stato osservato davvero

Stessa mattina:

- **08:00** — Briefing consegnato, **senza emoji**.
- **08:14** — **SECONDO** Briefing consegnato, **con emoji**.
- **09:00** — **TERZO** Briefing consegnato.

## Causa reale, con evidenza dal codice (non un'ipotesi)

`ProactiveManager.refreshMorningDigestNotification()` — chiamata **solo** da
`MorningRefreshWorker` (+10min/+60min dopo una consegna reale) — era l'UNICO
punto di tutto il codice capace di produrre una notifica dal contenuto del
Morning Briefing **senza alcun controllo di occorrenza** (`occurrence claim
= NO`): componeva e chiamava `notifier.show(...)` direttamente, assumendo
(mai verificando) che l'occorrenza di oggi fosse davvero `DELIVERED`.

Aggravante reale, non ipotizzata: `ProactiveNotifier.show()` aveva già
`.setOnlyAlertOnce(true)` (da un fix precedente) — ma quel flag sopprime un
nuovo avviso sonoro/vibrazione/heads-up **solo se la notifica con quello
stesso id è ancora presente nello shade**. Non appena l'utente ha
letto/aperto/eliminato la notifica delle 08:00 (assolutamente plausibile
entro 10-14 minuti), la successiva `notify()` dello stesso id viene trattata
da Android come **una notifica nuova** — sonora, con heads-up — a tutti gli
effetti indistinguibile da un secondo/terzo messaggio reale. `+10min` (con
lo slack tipico di WorkManager sotto vincoli Doze/batteria) spiega l'orario
08:14; `+60min` da 08:00 combacia esattamente con 09:00.

**Perché la differenza di contenuto (senza emoji → con emoji)**: lo STESSO
composer (`ProactiveComposer.morningDigest`, mai toccato, mai duplicato) fu
invocato due volte su due `snapshot()` presi in istanti diversi — alle 08:00
`contextEngine.todayWeather()` non aveva ancora un dato meteo noto (emoji
omessa per design, mai indovinata), mentre entro le 08:14 un
`weather.refresh()` successivo (lo stesso identico refresh chiamato ad ogni
`snapshot()`) aveva nel frattempo ottenuto una categoria reale. Non una
seconda implementazione, non un renderer diverso — la stessa funzione pura,
letta due volte a distanza di minuti con dati sottostanti diversi.

## Delivery Path Matrix (§2 del mandato)

Ogni percorso reale trovato nell'app capace di produrre semantica Morning
Briefing/"Buongiorno", verificato per codice (non per nome di classe) — mai
una supposizione.

| Trigger source | Scheduler/alarm/work identity | Receiver/worker/service | Composer/renderer | ProactiveKind | Logical date | Occurrence key | Claim called? | Governor used? | Notifier | Notif tag/id | setOnlyAlertOnce | Emoji-capable | Retry behavior | Stale-instance behavior |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| CONFIGURED_TIME | `MorningTriggerScheduler.scheduleConfiguredTimeTrigger()` → `ExactAlarms` (key=`KEY_CONFIGURED_TIME`, `FLAG_UPDATE_CURRENT`) | `AlarmReceiver` (`KIND_MORNING_BRIEFING`) → `ProactiveManager.evaluateOnUnlock` | `ProactiveComposer.morningDigest` v1 | MORNING_DIGEST | today | `MORNING_DIGEST:<date>` | **YES** (`occurrenceStore.claim`) | YES | `ProactiveNotifier.show` | —/7200+ordinal | YES (`true`) | YES | `FAILED_RETRYABLE` releases claim | Costante per key → un reschedule sostituisce sempre in place (verificato, mai un secondo alarm) |
| NEXT_ALARM | `MorningTriggerScheduler.scheduleNextAlarmTrigger()` → `ExactAlarms` (key=`KEY_NEXT_ALARM`) | stesso `AlarmReceiver` | stesso | MORNING_DIGEST | today | stesso formato | **YES** | YES | stesso | stesso | YES | YES | stesso | stesso meccanismo, key propria costante |
| FIRST_UNLOCK | evento reale `ACTION_USER_PRESENT` | `AutomationEventService.onUnlock()` → `ProactiveManager.evaluateOnUnlock` | stesso | MORNING_DIGEST | today | stesso | **YES** | YES | stesso | stesso | YES | YES | stesso | N/A (evento, non un alarm schedulato) |
| PERIODIC_FALLBACK | `ProactiveScheduler` (WorkManager periodico 1h) | `ProactiveWorker` → `ProactiveManager.evaluate` | stesso | MORNING_DIGEST | today | stesso | **YES** | YES | stesso | stesso | YES | YES | stesso | `ExistingPeriodicWorkPolicy.KEEP`, mai duplicato |
| MANUAL | tocco "Controlla adesso" | `DiagnosticsViewModel.refreshProactiveDiagnostics()` → `evaluateOnUnlock` | stesso | MORNING_DIGEST | today | stesso | **YES** | YES | stesso | stesso | YES | YES | stesso | N/A |
| **POST_BRIEFING_REFRESH (+10min/+60min)** | `MorningTriggerScheduler.schedulePostBriefingRefreshes()` → WorkManager one-shot univoco (`ExistingWorkPolicy.KEEP`) | `MorningRefreshWorker` → `ProactiveManager.refreshMorningDigestNotification` | stesso composer, `snapshot()` riletto | MORNING_DIGEST | today | stesso | **PRIMA: NO (bug) → ORA: SÌ, `peek()` read-only via `MorningRefreshGate`** | **NO — deliberatamente** (§ refresh, non una nuova decisione) | stesso | stesso id, **notify() diretto** | YES ma **insufficiente da solo** (vedi causa reale) | YES — **questa è la fonte della differenza di contenuto osservata** | mai un secondo tentativo oltre i due schedulati | `ExistingWorkPolicy.KEEP` per data, mai duplicato |
| KIND_REMINDER (promemoria agenda) | `ReminderScheduler`/`ExactAlarms` per singolo entry+alert | `AlarmReceiver` (`KIND_REMINDER`) → `notifyReminder()` | testo libero dell'impegno, mai "Buongiorno" | N/A (nessun ProactiveKind) | N/A | N/A — id per entry/alert, mai `MORNING_DIGEST:<date>` | N/A | NO (non passa mai da `ProactiveManager`) | `NotificationManagerCompat` diretto, MAI `ProactiveNotifier` | id derivato da `id.hashCode()`, namespace distinto | N/A | NO | N/A | N/A — verificato strutturalmente distinto (regression test) |
| KIND_AUTOMATION (motore 6f legacy) | regola utente-autorata | `AlarmReceiver` (`KIND_AUTOMATION`) → `AutomationRunner` | contenuto scelto dall'utente in fase di creazione regola | N/A | N/A | id proprio (`NOTIFICATION_BASE` di `AutomationRunner`) | N/A | NO | `NotificationManagerCompat` diretto | namespace distinto | N/A | N/A (testo libero) | N/A | Nessuna sovrapposizione strutturale con `ProactiveKind.MORNING_DIGEST` — solo se un utente ricrea manualmente un testo "Buongiorno" in una propria automazione, fuori scope codice |
| Boot/riavvio | `BootReceiver` | → `morningTriggerScheduler.scheduleAll()` | — (nessuna generazione, solo re-arm) | — | — | — | NO (non genera nulla di suo) | — | — | — | — | — | Ri-arma entrambi i segnali sotto le stesse chiavi costanti, mai duplica |
| App start | `JarvisApplication.onCreate()` | → `morningTriggerScheduler.scheduleAll()` | — | — | — | — | NO | — | — | — | — | — | — | stesso |

**Unico finding critico trovato** (`occurrence claim = NO`): la riga
POST_BRIEFING_REFRESH, PRIMA di questo micro-patch — l'unico percorso
dell'intera app capace di produrre contenuto Morning-Briefing-shaped senza
mai interrogare `ProactiveOccurrenceStore`/`ProactiveOccurrenceReconciler`.
Corretto (colonna "ORA" sopra).

## La correzione (MICRO-PATCH 14.2.2)

1. `MorningRefreshGate.shouldRefresh(state)` (`:core`, puro) — un refresh può
   procedere **solo** se l'occorrenza di oggi è genuinamente `DELIVERED`,
   verificato con una lettura read-only (`ProactiveOccurrenceStore.peek()`,
   mai una nuova claim) — mai più assunto.
2. `ProactiveNotifier.show(suggestion, silent = true)` — il percorso di
   refresh posta ora **sempre** in silenzio (`NotificationCompat.Builder.setSilent(true)`,
   una garanzia incondizionata, non legata allo stato di dismissal della
   notifica precedente) — può aggiornare il contenuto ma non può mai più
   diventare un secondo avviso sonoro/heads-up.
3. Ricevute diagnostiche bounded (Diagnostica › debug) per ogni tentativo,
   da ogni fonte di trigger.

## Checklist AGGIORNATA — gate severo (§11 del mandato)

Sostituisce/estende gli scenari A-G sopra con il criterio più severo
richiesto esplicitamente: automatico + CI **non chiudono** questo gate.

1. Imposta l'orario del briefing a pochi minuti da adesso (Impostazioni ›
   Proattività › Orario briefing).
2. Assicurati che NEXT_ALARM sia anch'esso rilevante (una sveglia di sistema
   impostata, se possibile, vicino allo stesso orario).
3. Sblocca il telefono vicino all'orario configurato.
4. Il processo dell'app può riavviarsi liberamente durante il test (kill
   dal task switcher, o naturale da parte del sistema).
5. Atteso: **ESATTAMENTE UNA** notifica Morning Briefing.
6. Atteso: stile emoji/contenuto invariato rispetto a prima di questo
   micro-patch (nessuna riformulazione, `ProactiveComposer.kt` non toccato).
7. Attendi **almeno 90 minuti** senza toccare il telefono (copre sia il
   refresh +10min sia il +60min per intero).
8. Atteso: **ZERO** notifiche aggiuntive nei 90 minuti (un eventuale
   aggiornamento silenzioso del contenuto della STESSA notifica è
   accettabile e atteso — un secondo elemento visibile nello shade, o un
   secondo avviso sonoro/heads-up, NON lo è).
9. Cambia di nuovo l'orario configurato DOPO che il briefing di oggi è già
   stato consegnato.
10. Atteso: **ZERO** nuove consegne lo stesso giorno logico.
11. Apri Diagnostica › "Briefing mattutino — ricevute di consegna (debug)" e
    verifica che i trigger successivi al primo mostrino `claim=ALREADY_*`/
    `esito=SKIPPED` o `REFRESHED_SILENT` — mai un secondo `DELIVERED` per la
    stessa data logica.

## Onestà — Honor 200 acceptance (MICRO-PATCH 14.2.2)

Nessuno dei passi 1-11 sopra è stato (ri)eseguito da questo ambiente (nessun
dispositivo Android disponibile qui) — il precedente run reale sul
dispositivo dell'utente è quello che ha rivelato il bug qui corretto.
**Stato: FAILED — RETEST REQUIRED.** Non dichiarare PASSED finché il
checklist aggiornato non è stato eseguito davvero sull'Honor 200 con questa
build.
